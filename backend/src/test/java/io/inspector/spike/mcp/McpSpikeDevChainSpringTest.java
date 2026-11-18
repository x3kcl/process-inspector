package io.inspector.spike.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.NoDbTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SPIKE #492 S0 (NOT FOR MERGE) — U1 + U2 on the dev ({@code !oidc}) chain, via MockMvc over the real
 * filter chain and the real Spring AI stateless transport. Each test prints the captured exchange.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
@AutoConfigureMockMvc
class McpSpikeDevChainSpringTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper jackson2; // the app's Jackson 2 mapper — only to read responses here

    static String call(String tool) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":{}}}";
    }

    static final String LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}";

    MockHttpServletResponse mcp(String body, RequestPostProcessor who) throws Exception {
        var req = post("/mcp")
                .contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .content(body);
        if (who != null) {
            req.with(who);
        }
        MockHttpServletResponse resp = mvc.perform(req).andReturn().getResponse();
        System.out.println("SPIKE-EXCHANGE POST /mcp " + body + "\n  -> " + resp.getStatus() + " "
                + resp.getHeaderNames().stream()
                        .filter(h -> !h.equalsIgnoreCase("Set-Cookie"))
                        .map(h -> h + "=" + resp.getHeader(h))
                        .toList()
                + "\n  " + resp.getContentAsString());
        return resp;
    }

    @Test
    void toolsListAnswersForABasicAuthenticatedViewer() throws Exception {
        var resp = mcp(LIST, httpBasic("viewer", "dev"));
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getContentAsString()).contains("whoami_engines");
    }

    @Test
    void theToolSeesTheDevPrincipalOnTheRequestThread() throws Exception {
        var resp = mcp(call("whoami_engines"), httpBasic("viewer", "dev"));
        assertThat(resp.getStatus()).isEqualTo(200);
        JsonNode result = jackson2.readTree(resp.getContentAsString()).path("result");
        assertThat(result.path("isError").asBoolean(false))
                .as(result.toString())
                .isFalse();
        JsonNode data = result.path("structuredContent");
        if (data.isMissingNode() || data.isNull()) {
            data = jackson2.readTree(result.path("content").get(0).path("text").asText());
        }
        assertThat(data.path("principal").asText()).isEqualTo("viewer");
        assertThat(data.path("authType").asText()).isEqualTo("UsernamePasswordAuthenticationToken");
        // dev ladder user = global VIEWER: every test engine, VIEWER on each
        assertThat(data.path("engines").path("probe-dev").asText()).isEqualTo("VIEWER");
        assertThat(data.path("engines").path("probe-prod").asText()).isEqualTo("VIEWER");
        System.out.println("SPIKE-THREAD " + data.path("thread") + " virtual=" + data.path("virtual"));
    }

    @Test
    void preAuthorizeOnAToolMethodRefusesAViewer() throws Exception {
        var resp = mcp(call("spike_admin_only"), httpBasic("viewer", "dev"));
        // THE dangerous failure mode: if the annotation were silently ignored, the payload would leak.
        assertThat(resp.getContentAsString())
                .as("@PreAuthorize on @McpTool must not be silently ignored")
                .doesNotContain("secret-admin-payload");
    }

    @Test
    void preAuthorizeOnAToolMethodAdmitsAnAdmin() throws Exception {
        var resp = mcp(call("spike_admin_only"), httpBasic("admin", "dev"));
        assertThat(resp.getContentAsString()).contains("secret-admin-payload");
    }

    @Test
    void anUnauthenticatedMcpRequestIsRefusedWith401() throws Exception {
        var resp = mcp(LIST, null);
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void aRolelessDevPrincipalGetsTheRoleFloorRefusal() throws Exception {
        // `metrics` is the dev account with no ladder rung (#772) — the closest dev twin of #758's unmapped user.
        var resp = mcp(call("whoami_engines"), httpBasic("metrics", "dev"));
        assertThat(resp.getContentAsString()).contains("no-engine-role").doesNotContain("probe-dev");
    }
}
