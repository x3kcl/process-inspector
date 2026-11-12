package io.inspector.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.inspector.support.NoDbTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Issue #624, real-servlet rung: a browser closing its tab on {@code /api/bulk/events} is
 * noticed twice — by the next failed write in {@code SseHub.sendToOne} and by the emitter's
 * {@code onError} callback the container fires for the same stream. Only a real Tomcat fires
 * that second path, so MockMvc cannot prove this. One ordinary disconnect must add exactly one
 * to {@code sse_emitter_disconnects_total} and nothing to {@code sse_emitter_errors_total}
 * (the counter behind SseEmitterErrorsSpiking), and the active gauge must return to where it was.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class SseDisconnectSpringTest {

    @LocalServerPort
    int port;

    @Autowired
    SseHub hub;

    @Autowired
    MeterRegistry metrics;

    @Test
    void anOrdinaryClientCloseCountsOnceAsADisconnectAndNeverAsAnError() throws Exception {
        int liveBefore = hub.subscriberCount();
        double errorsBefore = count("sse_emitter_errors_total");
        double disconnectsBefore = count("sse_emitter_disconnects_total");

        try (Socket socket = new Socket("localhost", port)) {
            String basic = Base64.getEncoder().encodeToString("viewer:dev".getBytes(StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            out.write(("GET /api/bulk/events HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\n"
                            + "Authorization: Basic " + basic + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            assertThat(in.readLine()).startsWith("HTTP/1.1 200");
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(hub.subscriberCount()).isEqualTo(liveBefore + 1));
            socket.setSoLinger(true, 0); // close with RST, like a killed tab
        }

        // The hub only learns of the close on its next write; ping until it does.
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    hub.heartbeat();
                    return count("sse_emitter_disconnects_total") > disconnectsBefore;
                });

        // The container's onError arrives asynchronously after the failed write: hold the
        // assertion long enough for a second count to land if it were going to.
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(count("sse_emitter_disconnects_total") - disconnectsBefore)
                    .isEqualTo(1.0);
            assertThat(count("sse_emitter_errors_total") - errorsBefore).isZero();
            assertThat(hub.subscriberCount()).isEqualTo(liveBefore);
            assertThat(metrics.get("sse_emitters_active").gauge().value()).isEqualTo(liveBefore);
        });
    }

    private double count(String name) {
        return metrics.get(name).counter().count();
    }
}
