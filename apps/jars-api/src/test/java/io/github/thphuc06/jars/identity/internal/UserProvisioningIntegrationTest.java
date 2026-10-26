package io.github.thphuc06.jars.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import io.github.thphuc06.jars.TestcontainersConfiguration;
import io.github.thphuc06.jars.identity.UserRegistered;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Flow F1 end to end on PostgreSQL 17: token → GET /me → user row created once, even under concurrency → (after
 * commit, asynchronously) the ledger creates the 7 default jars.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(TestcontainersConfiguration.class)
class UserProvisioningIntegrationTest {

    static final SimpleGrantedAuthority READ = new SimpleGrantedAuthority("SCOPE_jars-api/read");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    UserRegistrar registrar;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    @MockitoBean
    UserInfoClient userInfo;

    private long jarsFor(String sub) {
        return jdbc.sql("SELECT count(*) FROM jars j JOIN users u ON u.id = j.user_id WHERE u.cognito_sub = ?")
                .param(sub)
                .query(Long.class)
                .single();
    }

    /** The ledger listener runs on another thread after commit, so the jars appear shortly after the response. */
    private void awaitDefaultJars(String sub) {
        await().atMost(Duration.ofSeconds(10)).until(() -> jarsFor(sub) == 7);
    }

    private long rowsFor(String sub) {
        return jdbc.sql("SELECT count(*) FROM users WHERE cognito_sub = ?")
                .param(sub)
                .query(Long.class)
                .single();
    }

    @Test
    void firstCall_createsUser_secondCall_reusesItWithoutCallingCognitoAgain() {
        String sub = "sub-" + UUID.randomUUID();
        given(userInfo.fetch(anyString())).willReturn(new UserInfoClient.UserInfo(sub, "first@example.com"));

        for (int call = 1; call <= 2; call++) {
            assertThat(mvc.get()
                            .uri("/api/v1/me")
                            .with(jwt().jwt(j -> j.subject(sub)).authorities(READ)))
                    .hasStatus(HttpStatus.OK)
                    .bodyJson()
                    .extractingPath("$.email")
                    .isEqualTo("first@example.com");
        }

        assertThat(rowsFor(sub)).isEqualTo(1);
        verify(userInfo, times(1)).fetch(anyString()); // only the first request needed the e-mail
        assertThat(events.stream(UserRegistered.class)).hasSize(1);
        awaitDefaultJars(sub);
    }

    @Test
    void sameEmailUnderANewCognitoAccount_is409EmailAlreadyRegistered() {
        // e.g. the user deleted their Cognito account and signed up again: new sub, same e-mail
        String email = "reused-" + UUID.randomUUID() + "@example.com";
        registrar.register("old-sub-" + UUID.randomUUID(), email);
        String newSub = "new-sub-" + UUID.randomUUID();
        given(userInfo.fetch(anyString())).willReturn(new UserInfoClient.UserInfo(newSub, email.toUpperCase()));

        assertThat(mvc.get()
                        .uri("/api/v1/me")
                        .with(jwt().jwt(j -> j.subject(newSub)).authorities(READ)))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.type")
                .isEqualTo("https://jars.local/problems/email-already-registered");
        assertThat(rowsFor(newSub)).isZero();
    }

    @Test
    void concurrentFirstRequests_createExactlyOneUserAndOneEvent() throws Exception {
        String sub = "sub-" + UUID.randomUUID();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);

        List<UUID> ids;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<UUID>> calls = IntStream.range(0, threads)
                    .<Callable<UUID>>mapToObj(i -> () -> {
                        start.await(); // release all threads at once to maximise the race
                        return registrar.register(sub, "race@example.com").id();
                    })
                    .toList();
            List<Future<UUID>> futures = calls.stream().map(pool::submit).toList();
            start.countDown();
            ids = futures.stream()
                    .map(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                    })
                    .toList();
        }

        assertThat(ids).hasSize(threads).containsOnly(ids.getFirst()); // everybody got the same user
        assertThat(rowsFor(sub)).isEqualTo(1);
        assertThat(events.stream(UserRegistered.class).filter(e -> e.userId().equals(ids.getFirst())))
                .hasSize(1);
        awaitDefaultJars(sub); // exactly one event, so exactly one set of jars
    }
}
