package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.NoDbTestSupport;
import io.inspector.views.SavedView;
import io.inspector.views.SavedViewRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Rung 3: the {@code /api/views} + {@code /api/recents} contract — wiring, VIEWER gate, and the
 * critical ownership property: the store is keyed on {@code authentication.getName()}, NEVER a
 * client-supplied owner. Repos are mocked (NoDbTestSupport); the real JDBC path is rung 4.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class ViewsApiSpringTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    SavedViewRepository savedViews;

    @Test
    void listsOnlyTheCallersViews() throws Exception {
        when(savedViews.findByOwnerOrderByCreatedAtDesc("viewer"))
                .thenReturn(List.of(
                        new SavedView("viewer", "Mine", "status=FAILED", Instant.parse("2026-07-09T09:00:00Z"))));

        ResponseEntity<String> res = rest.withBasicAuth("viewer", "dev").getForEntity("/api/views", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(res.getBody());
        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("name").asText()).isEqualTo("Mine");
        assertThat(body.get(0).get("search").asText()).isEqualTo("status=FAILED");
    }

    @Test
    void saveKeysOnThePrincipalNotAnyClientField() {
        when(savedViews.findByOwnerAndName(eq("operator"), any())).thenReturn(Optional.empty());
        when(savedViews.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // A crafted body cannot set the owner — the server uses authentication.getName().
        ResponseEntity<String> res = rest.withBasicAuth("operator", "dev")
                .exchange(
                        "/api/views",
                        org.springframework.http.HttpMethod.PUT,
                        new org.springframework.http.HttpEntity<>(
                                java.util.Map.of("name", "v", "search", "status=ACTIVE", "owner", "someone-else")),
                        String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        var saved = org.mockito.ArgumentCaptor.forClass(SavedView.class);
        org.mockito.Mockito.verify(savedViews).save(saved.capture());
        assertThat(saved.getValue().getOwner()).isEqualTo("operator"); // NOT "someone-else"
    }

    @Test
    void deletingAViewYouDoNotOwnIs404() {
        when(savedViews.deleteByIdAndOwner(99L, "viewer")).thenReturn(0L);

        ResponseEntity<String> res = rest.withBasicAuth("viewer", "dev")
                .exchange("/api/views/99", org.springframework.http.HttpMethod.DELETE, null, String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void requiresAuthentication() {
        ResponseEntity<String> res = rest.getForEntity("/api/views", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
