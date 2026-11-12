package io.github.thphuc06.jars.notification.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.thphuc06.jars.TestcontainersConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Server-Sent Events over a real HTTP connection (the MockMvc tests cannot hold a stream open): live push, the
 * heartbeat, replay after a reconnect with {@code Last-Event-ID}, and several connections of one user.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, NotificationStreamTest.AcceptTestTokens.class})
class NotificationStreamTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final String CATEGORY_FOOD = "018f0000-0000-7000-8000-000000000001";

    /** Real HTTP cannot use MockMvc's {@code jwt()}, so tests send "Bearer test-&lt;subject&gt;" and this decoder accepts it. */
    @TestConfiguration(proxyBeanMethods = false)
    static class AcceptTestTokens {
        @Bean
        @Primary
        JwtDecoder testTokenDecoder() {
            return token -> {
                if (!token.startsWith("test-")) {
                    throw new BadJwtException("not a test token");
                }
                Instant now = Instant.now();
                return Jwt.withTokenValue(token)
                        .header("alg", "none")
                        .subject(token.substring("test-".length()))
                        .claim("token_use", "access")
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(3600))
                        .build();
            };
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    SseRegistry registry;

    @Autowired
    JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void anOpenConnection_receivesTheNotificationTheMomentAnAnomalyIsDetected() throws Exception {
        String sub = newSubject();
        String nec = necJar(sub);
        try (SseClient client = SseClient.open(http, url("/api/v1/notifications/stream"), sub, null)) {
            client.awaitLine(l -> l.contains("connected"));

            spikeAfterTraining(sub, nec);

            String id = client.awaitLine(l -> l.startsWith("id:"));
            String event = client.awaitLine(l -> l.startsWith("event:"));
            String data = client.awaitLine(l -> l.startsWith("data:"));
            assertThat(event).isEqualTo("event:notification");
            JsonNode n = json.readTree(data.substring("data:".length()));
            assertThat(id).isEqualTo("id:" + n.get("id").asString());
            assertThat(n.get("title").asString()).contains("Thiết yếu").contains("bất thường");
            assertThat(n.get("severity").asString()).isEqualTo("HIGH");
            assertThat(n.get("type").asString()).isEqualTo("ANOMALY");
        }
    }

    @Test
    void theHeartbeatKeepsAnIdleConnectionAlive() throws Exception {
        String sub = newSubject();
        necJar(sub); // creates the user
        try (SseClient client = SseClient.open(http, url("/api/v1/notifications/stream"), sub, null)) {
            client.awaitLine(l -> l.contains("connected"));

            registry.sendHeartbeats(); // what the 15-second timer does

            assertThat(client.awaitLine(l -> l.contains("heartbeat"))).startsWith(":");
        }
    }

    @Test
    void everyOpenConnectionOfAUserGetsTheNotification() throws Exception {
        String sub = newSubject();
        String nec = necJar(sub);
        try (SseClient tab1 = SseClient.open(http, url("/api/v1/notifications/stream"), sub, null);
                SseClient tab2 = SseClient.open(http, url("/api/v1/notifications/stream"), sub, null)) {
            tab1.awaitLine(l -> l.contains("connected"));
            tab2.awaitLine(l -> l.contains("connected"));

            spikeAfterTraining(sub, nec);

            assertThat(tab1.awaitLine(l -> l.startsWith("data:"))).contains("Thiết yếu");
            assertThat(tab2.awaitLine(l -> l.startsWith("data:"))).contains("Thiết yếu");
        }
    }

    @Test
    void aClientThatReconnects_isSentOnlyWhatItMissed() throws Exception {
        String sub = newSubject();
        String nec = necJar(sub);
        spikeAfterTraining(sub, nec);
        String firstId = await().atMost(WAIT)
                .until(() -> notificationIds(sub), ids -> !ids.isEmpty())
                .getFirst();
        postExpense(sub, nec, "2000000"); // a second alert while the client is "offline"
        await().atMost(WAIT).until(() -> notificationIds(sub).size() == 2);
        String secondId = notificationIds(sub).stream()
                .filter(id -> !id.equals(firstId))
                .findFirst()
                .orElseThrow();

        try (SseClient client = SseClient.open(http, url("/api/v1/notifications/stream"), sub, firstId)) {
            String data = client.awaitLine(l -> l.startsWith("data:"));

            assertThat(data).contains(secondId).doesNotContain(firstId);
        }
    }

    @Test
    void theStreamNeedsAToken() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(url("/api/v1/notifications/stream"))
                        .GET()
                        .build(),
                BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/problem+json");
    }

    // ------------------------------------------------------------------ helpers

    private void spikeAfterTraining(String sub, String jar) throws Exception {
        int[] steady = {
            95_000, 105_000, 98_000, 102_000, 100_000, 97_000, 103_000, 99_000, 101_000, 100_000, 96_000, 104_000
        };
        for (int amount : steady) {
            postExpense(sub, jar, String.valueOf(amount));
        }
        // the detector runs after commit, asynchronously: wait until all twelve are in the dashboard before the spike
        await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).until(() -> {
            JsonNode d =
                    json.readTree(call("GET", "/api/v1/dashboard", sub, null).body());
            for (JsonNode j : d.get("spendByJar")) {
                if (j.get("jarId").asString().equals(jar)) {
                    return j.get("txnCount").asInt() >= steady.length;
                }
            }
            return false;
        });
        postExpense(sub, jar, "2000000");
    }

    private void postExpense(String sub, String jar, String amount) throws Exception {
        String body = "{\"type\":\"EXPENSE\",\"amount\":" + amount + ",\"jarId\":\"" + jar + "\",\"categoryId\":\""
                + CATEGORY_FOOD + "\"}";
        HttpResponse<String> response = call("POST", "/api/v1/transactions", sub, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    private String necJar(String sub) throws Exception {
        HttpResponse<String> jars = call("GET", "/api/v1/jars", sub, null);
        assertThat(jars.statusCode()).as(jars.body()).isEqualTo(200);
        for (JsonNode j : json.readTree(jars.body())) {
            if (j.get("code").asString().equals("NEC")) {
                return j.get("id").asString();
            }
        }
        throw new AssertionError("no NEC jar");
    }

    private List<String> notificationIds(String sub) throws Exception {
        JsonNode page = json.readTree(
                call("GET", "/api/v1/notifications?limit=100", sub, null).body());
        List<String> ids = new java.util.ArrayList<>();
        page.get("items").forEach(n -> ids.add(n.get("id").asString()));
        return ids;
    }

    private HttpResponse<String> call(String method, String path, String sub, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(url(path))
                .header("Authorization", "Bearer test-" + sub)
                .header("Content-Type", "application/json");
        if ("POST".equals(method)) {
            request.header("Idempotency-Key", UUID.randomUUID().toString()).POST(BodyPublishers.ofString(body));
        } else {
            request.GET();
        }
        return http.send(request.build(), BodyHandlers.ofString());
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String newSubject() {
        return "sse-" + UUID.randomUUID();
    }

    /** Reads an SSE response line by line on a background thread so the test can wait for specific lines. */
    static final class SseClient implements AutoCloseable {

        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final List<String> seen = new CopyOnWriteArrayList<>();
        private final Thread reader;
        private volatile Stream<String> stream;

        private SseClient(HttpClient http, URI uri, String subject, String lastEventId) {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer test-" + subject)
                    .header("Accept", "text/event-stream")
                    .GET();
            if (lastEventId != null) {
                request.header("Last-Event-ID", lastEventId);
            }
            reader = Thread.ofVirtual().unstarted(() -> {
                try {
                    HttpResponse<Stream<String>> response = http.send(request.build(), BodyHandlers.ofLines());
                    stream = response.body();
                    stream.forEach(line -> {
                        seen.add(line);
                        lines.add(line);
                    });
                } catch (IOException | InterruptedException | RuntimeException e) {
                    // closing the client interrupts the read; that is the normal end of the test
                }
            });
            reader.start();
        }

        static SseClient open(HttpClient http, URI uri, String subject, String lastEventId) {
            return new SseClient(http, uri, subject, lastEventId);
        }

        /** The next line matching {@code wanted}; lines before it are discarded. Fails after 20 s. */
        String awaitLine(Predicate<String> wanted) throws InterruptedException {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (true) {
                long left = deadline - System.nanoTime();
                String line = left <= 0 ? null : lines.poll(left, TimeUnit.NANOSECONDS);
                if (line == null) {
                    throw new AssertionError("timed out waiting for an SSE line; saw: " + seen);
                }
                if (wanted.test(line)) {
                    return line;
                }
            }
        }

        @Override
        public void close() {
            reader.interrupt();
            Stream<String> s = stream;
            if (s != null) {
                s.close();
            }
        }
    }
}
