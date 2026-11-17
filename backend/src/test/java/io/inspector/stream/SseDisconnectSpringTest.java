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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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
        assertClosesCountOnceAsDisconnects(1, true);
    }

    /**
     * A graceful close (FIN, not RST) lets the first write after it land in the kernel buffer;
     * only a later write fails. Several streams closing at once must still add exactly one
     * disconnect each and no error — the review's "50 clients close -> +100" shape, at real TCP.
     */
    @Test
    void severalGracefulClosesCountOnceEachAsDisconnects() throws Exception {
        assertClosesCountOnceAsDisconnects(5, false);
    }

    private void assertClosesCountOnceAsDisconnects(int clients, boolean reset) throws Exception {
        int liveBefore = hub.subscriberCount();
        double errorsBefore = count("sse_emitter_errors_total");
        double disconnectsBefore = count("sse_emitter_disconnects_total");

        List<Socket> sockets = new ArrayList<>();
        try {
            for (int i = 0; i < clients; i++) {
                sockets.add(openStream());
            }
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(hub.subscriberCount()).isEqualTo(liveBefore + clients));
        } finally {
            for (Socket socket : sockets) {
                if (reset) {
                    socket.setSoLinger(true, 0); // close with RST, like a killed tab
                }
                socket.close();
            }
        }

        // The hub only learns of a close on a later write; ping until every stream is counted.
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    hub.heartbeat();
                    return count("sse_emitter_disconnects_total") - disconnectsBefore >= clients;
                });

        // The container's onError arrives asynchronously after the failed write: hold the
        // assertion long enough for a second count to land if it were going to.
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            hub.heartbeat();
            assertThat(count("sse_emitter_disconnects_total") - disconnectsBefore)
                    .isEqualTo(clients);
            assertThat(count("sse_emitter_errors_total") - errorsBefore).isZero();
            assertThat(hub.subscriberCount()).isEqualTo(liveBefore);
            assertThat(metrics.get("sse_emitters_active").gauge().value()).isEqualTo(liveBefore);
        });
    }

    /** Opens one {@code /api/bulk/events} stream and waits for its 200 status line. */
    private Socket openStream() throws Exception {
        Socket socket = new Socket("localhost", port);
        socket.setSoTimeout(5_000); // a stuck stream fails the test instead of hanging it
        String basic = Base64.getEncoder().encodeToString("viewer:dev".getBytes(StandardCharsets.UTF_8));
        OutputStream out = socket.getOutputStream();
        out.write(("GET /api/bulk/events HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\n"
                        + "Authorization: Basic " + basic + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        BufferedReader in =
                new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        // The hub sends nothing on subscribe, so the headers only flush with the first event:
        // ping now instead of waiting up to 15 s for the scheduled heartbeat.
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            hub.heartbeat();
            return in.ready();
        });
        assertThat(in.readLine()).startsWith("HTTP/1.1 200");
        return socket;
    }

    private double count(String name) {
        return metrics.get(name).counter().count();
    }
}
