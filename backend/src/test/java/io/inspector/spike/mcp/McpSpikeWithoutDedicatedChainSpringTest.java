package io.inspector.spike.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.inspector.support.NoDbTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SPIKE #492 S0 (NOT FOR MERGE) — what happens if /mcp simply rides the existing dev chain (no
 * dedicated chain): an anonymous MCP POST is stopped by the SPA's CSRF filter with 403, BEFORE
 * authentication — so an MCP client never sees the 401 + WWW-Authenticate that starts OAuth discovery.
 */
@SpringBootTest(properties = "spike.mcp.chain.enabled=false")
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
@AutoConfigureMockMvc
class McpSpikeWithoutDedicatedChainSpringTest {

    @Autowired
    MockMvc mvc;

    @Test
    void anAnonymousMcpPostIs403ByCsrfNot401() throws Exception {
        var resp = mvc.perform(post("/mcp")
                        .contentType("application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .content(McpSpikeDevChainSpringTest.LIST))
                .andReturn()
                .getResponse();
        System.out.println("SPIKE-EXCHANGE anonymous POST /mcp on the unmodified dev chain -> " + resp.getStatus()
                + " WWW-Authenticate=" + resp.getHeader("WWW-Authenticate"));
        assertThat(resp.getStatus()).isEqualTo(403);
        assertThat(resp.getHeader("WWW-Authenticate")).isNull();
    }
}
