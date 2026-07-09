package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.NoDbTestSupport;
import io.inspector.views.SharedView;
import io.inspector.views.SharedViewRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Rung 3: the {@code /api/team-views} HTTP contract — wiring, the {@code @PreAuthorize} floors, and
 * DTO binding. Repos are mocked (NoDbTestSupport); the full publish/edit/unpublish + audit flow is
 * proven against a real Postgres in {@code SharedViewGovernanceIT} (rung 4). The scoped RBAC matrix
 * (per-engine can't publish global, etc.) is rung 1 over the authorizer — the dev basic-auth ladder
 * mints only GLOBAL grants, so it can't express scoped cases here.
 *
 * <p>Note {@code @Valid} on a {@code @RequestBody} runs BEFORE {@code @PreAuthorize} (argument
 * resolution precedes the method-security advice), so every door test sends a VALID body — a VIEWER
 * is then refused at the OPERATOR floor (403), while a dev ADMIN (global grant) clears both the floor
 * and the service scope gate. In the {@code test} profile engines are untenanted, so a concrete scope
 * derives a wildcard tenant → the ADMIN publish floor; the scoped OPERATOR cases are rung 1.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class TeamViewsApiSpringTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    SharedViewRepository sharedViews;

    @Test
    void listReturnsTheCallersVisibleTeamCanon() throws Exception {
        when(sharedViews.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(new SharedView(
                        "alice",
                        "Stuck payments",
                        "status=FAILED",
                        "*",
                        "*",
                        "the payments runbook",
                        null,
                        Instant.parse("2026-07-09T09:00:00Z"))));

        // A dev VIEWER holds a GLOBAL grant → overlaps the global-scoped canon → sees it.
        ResponseEntity<String> res = rest.withBasicAuth("viewer", "dev").getForEntity("/api/team-views", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(res.getBody());
        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("name").asText()).isEqualTo("Stuck payments");
        assertThat(body.get(0).get("author").asText()).isEqualTo("alice");
        assertThat(body.get(0).get("danglingReason").isNull()).isTrue(); // global scope never dangles
    }

    @Test
    void viewerCannotPublish() {
        // A VALID body (bean validation passes) so the @PreAuthorize OPERATOR floor is what refuses —
        // publishing is not a VIEWER capability. (@Valid on @RequestBody runs before @PreAuthorize, so
        // an invalid body would 400 regardless of role; a valid one isolates the door.)
        ResponseEntity<String> res = rest.withBasicAuth("viewer", "dev")
                .exchange(
                        "/api/team-views",
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of("name", "Stuck payments", "search", "status=FAILED")),
                        String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminPublishesThroughTheStackAndAuthorCannotBeForged() throws Exception {
        when(sharedViews.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        // A crafted "author" key is ignored (PublishRequest has no such field) — the server stamps
        // authentication.getName(). A dev ADMIN holds a global grant, so it clears the scope gate.
        ResponseEntity<String> res = rest.withBasicAuth("admin", "dev")
                .exchange(
                        "/api/team-views",
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of(
                                "name", "Stuck payments",
                                "search", "engines=orders-prod&status=FAILED",
                                "author", "someone-else")),
                        String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(res.getBody());
        assertThat(body.get("author").asText()).isEqualTo("admin"); // NOT "someone-else"
        assertThat(body.get("name").asText()).isEqualTo("Stuck payments");
    }

    @Test
    void requiresAuthentication() {
        ResponseEntity<String> res = rest.getForEntity("/api/team-views", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
