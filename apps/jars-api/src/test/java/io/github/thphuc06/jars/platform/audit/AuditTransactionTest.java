package io.github.thphuc06.jars.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import io.github.thphuc06.jars.TestcontainersConfiguration;
import io.github.thphuc06.jars.platform.security.UserDirectory;
import io.github.thphuc06.jars.shared.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.AspectJAroundAdvice;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The transactional contract of the audit aspect, on probe beans whose "business change" is a row in
 * {@code chat_sessions}: the audit row and that row exist together or not at all, whatever way the transaction ends.
 * Also the aspect order, the system actor, the clock, the guards for misplaced annotations, and the MDC of a request.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AuditTransactionTest.Probes.class})
class AuditTransactionTest {

    private static final Instant FIXED = Instant.parse("2026-11-13T05:00:00.123456789Z");

    // ------------------------------------------------------------- the probes

    /** A bean with the shapes of annotation the aspect has to handle (registered in {@link Probes}, not scanned). */
    static class Probe {

        private final JdbcClient jdbc;
        private final Inner inner;

        Probe(JdbcClient jdbc, Inner inner) {
            this.jdbc = jdbc;
            this.inner = inner;
        }

        private void business(UUID id) {
            jdbc.sql(
                            "INSERT INTO chat_sessions (id, user_id, title, created_at, updated_at) VALUES (?, ?, 'probe', now(), now())")
                    .params(id, UUID.randomUUID())
                    .update();
        }

        @Transactional
        @Audited(AuditAction.GOAL_CREATED)
        public UUID commits(UUID id) {
            business(id);
            Audit.entity(id).with("n", 1).with("flag", true).withCode("locale", "vi-VN");
            return id;
        }

        @Transactional
        @Audited(AuditAction.GOAL_CREATED)
        public void throwsAfterWork(UUID id) {
            business(id);
            Audit.entity(id);
            throw new IllegalStateException("boom");
        }

        @Transactional
        @Audited(AuditAction.GOAL_CREATED)
        public void failsAtCommit(UUID id) {
            business(id);
            Audit.entity(id); // the aspect inserts its row when this returns; then the commit is refused
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new OptimisticLockingFailureException("version conflict at commit");
                }
            });
        }

        @Transactional
        @Audited(AuditAction.GOAL_CREATED)
        public void forgetsTheEntity(UUID id) {
            business(id);
        }

        @Transactional
        @Audited(AuditAction.GOAL_CREATED)
        public void changesNothing(UUID id) {
            Audit.unchanged();
        }

        @Audited(AuditAction.GOAL_CREATED)
        public void withoutTransaction(UUID id) {
            Audit.entity(id);
        }

        @Transactional(readOnly = true)
        @Audited(AuditAction.GOAL_CREATED)
        public void readOnly(UUID id) {
            Audit.entity(id);
        }

        /** Two audited calls in one transaction: the outer frame is not disturbed by the inner one. */
        @Transactional
        @Audited(AuditAction.JAR_UPDATED)
        public void outer(UUID outerId, UUID innerId) {
            business(outerId);
            inner.audited(innerId);
            Audit.entity(outerId).with("level", 1);
        }

        /** The mistake the architecture test looks for: an audited method called on {@code this} skips the proxy. */
        @Transactional
        public void callsAuditedOnThis(UUID id) {
            audited(id);
        }

        @Audited(AuditAction.GOAL_CREATED)
        public void audited(UUID id) {
            Audit.entity(id);
        }

        /** No @Transactional: the transaction is the caller's, like the ledger's TransactionTemplate. */
        @Audited(AuditAction.GOAL_CREATED)
        public void commitsInsideAnOuterTransaction(UUID id) {
            business(id);
            Audit.entity(id);
        }
    }

    static class Inner {

        @Audited(AuditAction.JAR_CREATED)
        public void audited(UUID id) {
            Audit.entity(id).with("level", 2);
        }
    }

    /** What the request thread sees in its MDC. A controller of this test only (a bean of {@link Probes}). */
    @RestController
    @RequestMapping("/api/v1/probe")
    static class MdcProbe {

        @RequestMapping(path = "/mdc-user", method = RequestMethod.GET)
        Map<String, String> withUser(UserId user) {
            return seen();
        }

        @RequestMapping(path = "/mdc-no-user", method = RequestMethod.GET)
        Map<String, String> withoutUserLookup() {
            return seen();
        }

        @RequestMapping(path = "/mdc-fails", method = RequestMethod.GET)
        Map<String, String> fails(UserId user) {
            throw new IllegalStateException("after the user was resolved");
        }

        private static Map<String, String> seen() {
            Map<String, String> seen = new HashMap<>();
            seen.put("traceId", MDC.get("traceId"));
            seen.put("userId", MDC.get("userId"));
            return seen;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Probes {

        @Bean
        Inner inner() {
            return new Inner();
        }

        @Bean
        Probe probe(JdbcClient jdbc, Inner inner) {
            return new Probe(jdbc, inner);
        }

        @Bean
        MdcProbe mdcProbe() {
            return new MdcProbe();
        }

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneOffset.UTC);
        }
    }

    // ------------------------------------------------------------------ wiring

    @Autowired
    Probe probe;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    UserDirectory directory;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ApplicationContext context;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private int sessions(UUID id) {
        return jdbc.sql("SELECT count(*) FROM chat_sessions WHERE id = ?")
                .param(id)
                .query(Integer.class)
                .single();
    }

    private int auditRows(UUID entityId) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE entity_id = ?")
                .param(entityId)
                .query(Integer.class)
                .single();
    }

    private record Stored(String actor, String action, String entityType, Instant at, String payload) {}

    // ------------------------------------------------------------------ commit

    @Test
    void commit_leavesTheChangeAndExactlyOneRow() {
        UUID id = UUID.randomUUID();

        probe.commits(id);

        assertThat(sessions(id)).isEqualTo(1);
        assertThat(auditRows(id)).isEqualTo(1);
        Stored row = jdbc.sql(
                        "SELECT actor, action, entity_type, occurred_at, payload::text AS p FROM audit_log WHERE entity_id = ?")
                .param(id)
                .query((rs, n) -> new Stored(
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("entity_type"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        json.readTree(rs.getString("p")).toString()))
                .single();
        assertThat(row.actor()).as("no caller: the system").isEqualTo("system");
        assertThat(row.action()).isEqualTo("GOAL_CREATED");
        assertThat(row.entityType()).isEqualTo("GOAL");
        assertThat(row.at())
                .as("from the Clock bean, to the microsecond")
                .isEqualTo(Instant.parse("2026-11-13T05:00:00.123456Z"));
        assertThat(row.payload()).isEqualTo("{\"n\":1,\"flag\":true,\"locale\":\"vi-VN\"}");
    }

    // ---------------------------------------------------------------- rollback

    @Test
    void anExceptionInTheMethod_leavesNeitherTheChangeNorARow() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> probe.throwsAfterWork(id)).hasMessage("boom");

        assertThat(sessions(id)).isZero();
        assertThat(auditRows(id)).isZero();
    }

    @Test
    void aCommitThatIsRefused_takesTheAuditRowWithIt() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> probe.failsAtCommit(id)).isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(sessions(id)).isZero();
        assertThat(auditRows(id))
                .as("the row was inserted before the commit, and rolled back with it")
                .isZero();
    }

    @Test
    void aMethodThatDoesNotSayWhatItChanged_isRefused_andNothingStays() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> probe.forgetsTheEntity(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not call Audit.entity");

        assertThat(sessions(id)).as("no change without a row").isZero();
        assertThat(auditRows(id)).isZero();
    }

    @Test
    void aCallThatChangedNothing_writesNoRow() {
        UUID id = UUID.randomUUID();

        probe.changesNothing(id);

        assertThat(auditRows(id)).isZero();
    }

    @Test
    void aRetryOutsideTheTransaction_leavesOneRow_notOnePerAttempt() {
        UUID id = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        List<Integer> attempts = new ArrayList<>();

        // the shape of the ledger: retry loop outside, one transaction per attempt, the audited bean inside it
        for (int attempt = 1; ; attempt++) {
            int current = attempt;
            attempts.add(current);
            try {
                tx.executeWithoutResult(status -> {
                    probe.commitsInsideAnOuterTransaction(id);
                    if (current < 3) {
                        // the optimistic-lock conflict of a lost race surfaces at commit, after the row was inserted
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void beforeCommit(boolean readOnly) {
                                throw new OptimisticLockingFailureException("lost the race, attempt " + current);
                            }
                        });
                    }
                });
                break;
            } catch (OptimisticLockingFailureException e) {
                assertThat(auditRows(id))
                        .as("a lost attempt leaves nothing behind")
                        .isZero();
            }
        }

        assertThat(attempts).containsExactly(1, 2, 3);
        assertThat(sessions(id)).isEqualTo(1);
        assertThat(auditRows(id)).as("three attempts, one row").isEqualTo(1);
    }

    // ------------------------------------------------ order and misplaced uses

    @Test
    void theTransactionAdvisorIsOutsideTheAuditAdvisor() {
        BeanFactoryTransactionAttributeSourceAdvisor transaction =
                context.getBean(BeanFactoryTransactionAttributeSourceAdvisor.class);
        assertThat(transaction.getOrder())
                .isEqualTo(AuditAspect.TRANSACTION_ORDER)
                .isLessThan(AuditAspect.ORDER);

        // the real chain of a method that carries both: transaction first, audit second, then the method
        assertThat(AopUtils.isCglibProxy(probe)).isTrue();
        List<Class<?>> chain = new ArrayList<>();
        for (var advisor : ((Advised) probe).getAdvisors()) {
            chain.add(advisor.getAdvice().getClass());
        }
        int transactionAt = indexOf(chain, TransactionInterceptor.class);
        int auditAt = indexOf(chain, AspectJAroundAdvice.class);
        assertThat(transactionAt).as("chain: %s", chain).isNotNegative();
        assertThat(auditAt).as("chain: %s", chain).isGreaterThan(transactionAt);
    }

    private static int indexOf(List<Class<?>> chain, Class<?> type) {
        for (int i = 0; i < chain.size(); i++) {
            if (type.isAssignableFrom(chain.get(i))) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void anAuditedMethodWithoutATransaction_isRefused() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> probe.withoutTransaction(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without a read-write transaction");
        assertThatThrownBy(() -> probe.readOnly(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without a read-write transaction");

        assertThat(auditRows(id)).isZero();
    }

    @Test
    void anAuditedMethodCalledOnThis_failsLoudlyInsteadOfSkippingTheAudit() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> probe.callsAuditedOnThis(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside an @Audited call");

        assertThat(auditRows(id)).isZero();
    }

    @Test
    void auditOutsideAnAuditedCall_isRefused() {
        assertThatThrownBy(() -> Audit.entity(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(Audit::unchanged).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nestedAuditedCalls_eachWriteTheirOwnRow() {
        UUID outer = UUID.randomUUID();
        UUID innerId = UUID.randomUUID();

        probe.outer(outer, innerId);

        assertThat(auditRows(outer)).isEqualTo(1);
        assertThat(auditRows(innerId)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT payload::text FROM audit_log WHERE entity_id = ?")
                        .param(innerId)
                        .query(String.class)
                        .single())
                .isEqualTo("{\"level\": 2}");
        assertThat(jdbc.sql("SELECT payload::text FROM audit_log WHERE entity_id = ?")
                        .param(outer)
                        .query(String.class)
                        .single())
                .as("the inner call did not write into the outer frame")
                .isEqualTo("{\"level\": 1}");
    }

    @Test
    void theFrameDoesNotSurviveTheCall() {
        UUID id = UUID.randomUUID();
        probe.commits(id);
        assertThatThrownBy(() -> probe.throwsAfterWork(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);

        // a later call without a frame must not find a leftover one on this (reused) thread
        assertThatThrownBy(() -> Audit.entity(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ actor

    @Test
    void theActorIsTheUserOfTheToken_thatTheRestOfTheApplicationResolves() {
        String subject = "audit-probe-" + UUID.randomUUID();
        UserId expected = directory.resolve(subject);
        Jwt token =
                Jwt.withTokenValue("t").header("alg", "none").subject(subject).build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(token));
        SecurityContextHolder.setContext(context);
        UUID id = UUID.randomUUID();

        probe.commits(id);

        assertThat(jdbc.sql("SELECT actor FROM audit_log WHERE entity_id = ?")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo(expected.toString())
                .as("the internal id, not the Cognito subject")
                .isNotEqualTo(subject);
    }

    // --------------------------------------------------------------------- MDC

    @Test
    void theMdcCarriesTheTraceIdAndTheUserIdDuringARequest_andIsEmptyAfterwards() throws Exception {
        String subject = "mdc-" + UUID.randomUUID();
        String expectedUser = directory.resolve(subject).toString();

        MockHttpServletResponse response = mvc.perform(
                        MockMvcRequestBuilders.get("/api/v1/probe/mdc-user").with(jwt().jwt(j -> j.subject(subject))))
                .getResponse();

        Map<?, ?> seen = json.readValue(response.getContentAsString(), Map.class);
        assertThat(seen.get("userId"))
                .as("the internal id, never the e-mail or the sub")
                .isEqualTo(expectedUser);
        assertThat(seen.get("traceId"))
                .isEqualTo(response.getHeader("traceparent").substring(3, 35));
        assertThat(MDC.get("userId")).as("cleared when the request ended").isNull();
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void theUserIdOfOneRequestNeverLeaksIntoTheNext_onTheSameThread() throws Exception {
        String first = "mdc-first-" + UUID.randomUUID();
        String second = "mdc-second-" + UUID.randomUUID();
        UserId firstUser = directory.resolve(first);
        UserId secondUser = directory.resolve(second);

        Map<?, ?> one = seen("/api/v1/probe/mdc-user", first);
        Map<?, ?> two = seen("/api/v1/probe/mdc-user", second);
        Map<?, ?> noLookup = seen("/api/v1/probe/mdc-no-user", first);

        assertThat(one.get("userId")).isEqualTo(firstUser.toString());
        assertThat(two.get("userId")).isEqualTo(secondUser.toString());
        assertThat(noLookup.get("userId"))
                .as("a request that never resolved its user has no user id, whoever came before")
                .isNull();
        assertThat(noLookup.get("traceId")).isNotNull().isNotEqualTo(one.get("traceId"));
    }

    @Test
    void theMdcIsClearedWhenTheRequestFails() throws Exception {
        MockHttpServletResponse response = mvc.perform(MockMvcRequestBuilders.get("/api/v1/probe/mdc-fails")
                        .with(jwt().jwt(j -> j.subject("mdc-fail-" + UUID.randomUUID()))))
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("traceId")).isNull();
    }

    private Map<?, ?> seen(String uri, String subject) throws Exception {
        MockHttpServletResponse response = mvc.perform(
                        MockMvcRequestBuilders.get(uri).with(jwt().jwt(j -> j.subject(subject))))
                .getResponse();
        return json.readValue(response.getContentAsString(), Map.class);
    }

    @Test
    void auditRowsCarryTheTraceIdOfTheRequestThatWroteThem() {
        // the HTTP-level assertion lives in AuditLogApiTest; here the fallback without a request: no trace id at all
        UUID id = UUID.randomUUID();

        probe.commits(id);

        String traceId = jdbc.sql("SELECT trace_id FROM audit_log WHERE entity_id = ?")
                .param(id)
                .query((rs, n) -> rs.getString("trace_id"))
                .optional()
                .orElse(null);
        assertThat(traceId)
                .as("no request filter, no span: null rather than a made-up id")
                .isNull();
    }
}
