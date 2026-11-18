package io.inspector.spike.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.support.NoDbTestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * SPIKE #492 S0 (NOT FOR MERGE) — U2 threading on a REAL embedded Tomcat (MockMvc runs the request on
 * the JUnit thread, which proves nothing about the servlet thread). With
 * spring.threads.virtual.enabled=true the tool must run on the request's virtual thread and see the
 * SecurityContext the filter chain set there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class McpSpikeRealServerThreadTest {

    @LocalServerPort
    int port;

    @Test
    void theToolRunsOnTheVirtualRequestThreadWithTheSecurityContext() throws Exception {
        String basic = Base64.getEncoder().encodeToString("viewer:dev".getBytes());
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Basic " + basic)
                .POST(HttpRequest.BodyPublishers.ofString(McpSpikeDevChainSpringTest.call("whoami_engines")))
                .build();
        var resp = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println(
                "SPIKE-EXCHANGE real Tomcat POST /mcp whoami_engines -> " + resp.statusCode() + "\n  " + resp.body());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\\\"principal\\\":\\\"viewer\\\"").contains("\\\"virtual\\\":true");
    }
}
