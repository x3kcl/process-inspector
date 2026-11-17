package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.inspector.client.IdentityLinkRecord;
import io.inspector.client.ProcessApiClient;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.ReadScopeGate;
import io.inspector.support.NoDbTestSupport;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * #752 (R-SAFE-17): {@code GET /api/tasks/{engineId}/{taskId}/identitylinks} carries the same
 * {@code atLeastOn(VIEWER, #engineId)} method security as the instance identity-links read,
 * REGARDLESS of read-scope enforcement. A signed-in user with no role on the engine is refused
 * with the same 403 as every other per-engine read; a VIEWER reads.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
@AutoConfigureTestRestTemplate
class TaskIdentityLinksRoleTest {

    private static final String PATH = "/api/tasks/probe-prod/t-752/identitylinks";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    RbacAuthorizer rbac;

    @MockitoBean
    ReadScopeGate gate;

    @MockitoBean
    ProcessApiClient flowable;

    @BeforeEach
    void oneLink() {
        when(flowable.listIdentityLinks(any(), any(), anyString()))
                .thenReturn(
                        List.of(new IdentityLinkRecord("kermit", "kermit", null, null, "candidate", "t-752", null)));
        when(gate.canRead(any(Authentication.class), anyString())).thenReturn(true);
    }

    @Test
    void noRoleOnTheEngineIsRefusedWithEnforcementOff() throws Exception {
        assertRefused(false);
    }

    @Test
    void noRoleOnTheEngineIsRefusedWithEnforcementOn() throws Exception {
        assertRefused(true);
    }

    @Test
    void viewerReadsWithEnforcementOff() {
        assertViewerReads(false);
    }

    @Test
    void viewerReadsWithEnforcementOn() {
        assertViewerReads(true);
    }

    private void assertRefused(boolean enforced) throws Exception {
        when(gate.enforced()).thenReturn(enforced);
        when(rbac.atLeastOn(any(), eq("VIEWER"), eq("probe-prod"))).thenReturn(false);

        ResponseEntity<String> res = get(PATH);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refusal(res)).isEqualTo(refusal(get("/api/admin/audit/integrity")));
        verify(flowable, never()).listIdentityLinks(any(), any(), anyString());
    }

    private void assertViewerReads(boolean enforced) {
        when(gate.enforced()).thenReturn(enforced);
        when(rbac.atLeastOn(any(), eq("VIEWER"), eq("probe-prod"))).thenReturn(true);

        assertThat(get(PATH).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> get(String path) {
        return rest.withBasicAuth("viewer", "dev").getForEntity(path, String.class);
    }

    /** The refusal minus its per-request fields. */
    private Object refusal(ResponseEntity<String> res) throws Exception {
        ObjectNode body = (ObjectNode) mapper.readTree(res.getBody());
        body.remove(List.of("requestId", "instance", "path", "timestamp"));
        return body;
    }
}
