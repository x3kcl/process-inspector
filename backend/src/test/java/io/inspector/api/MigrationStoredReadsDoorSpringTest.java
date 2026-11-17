package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import io.inspector.migration.HierarchyPlan;
import io.inspector.migration.HierarchyPlanRepository;
import io.inspector.migration.MigrationAnalysis;
import io.inspector.migration.MigrationAnalysisRepository;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.Role;
import io.inspector.support.NoDbTestSupport;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * #773 item 1: the three stored migration reads sit behind the ADMIN door like the migration
 * writes. A non-admin gets the SAME status and body for a stored id and an unknown one (no
 * existence signal); an ADMIN keeps 200 / 404; and an ADMIN of ANOTHER engine than the stored
 * row's gets the unknown-id answer, not a 403 that would confirm the row exists.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "inspector.security.scope-reads-enforced=true")
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
@AutoConfigureTestRestTemplate
class MigrationStoredReadsDoorSpringTest {

    private static final String ENGINE = "probe-dev";
    private static final String OTHER = "probe-prod";
    private static final Instant AT = Instant.parse("2026-11-17T08:00:00Z");

    private static final String[] ROUTES = {
        "/api/migration/analyses/", "/api/case-migration/analyses/", "/api/migration/hierarchy/plans/"
    };

    @Autowired
    TestRestTemplate rest;

    @Autowired
    MigrationAnalysisRepository analyses;

    @Autowired
    HierarchyPlanRepository plans;

    @MockitoSpyBean
    RbacAuthorizer rbac;

    @AfterEach
    void resetMocks() {
        reset(analyses, plans);
    }

    private ResponseEntity<String> get(String user, String route, UUID id) {
        return rest.withBasicAuth(user, "dev").getForEntity(route + id, String.class);
    }

    /** Stores a row of the route's kind on {@code engine} and returns its id. */
    private UUID store(String route, String engine) {
        UUID id = UUID.randomUUID();
        if (route.contains("hierarchy")) {
            when(plans.findById(id))
                    .thenReturn(Optional.of(new HierarchyPlan(
                            id,
                            engine,
                            "admin",
                            AT,
                            "{\"rootInstanceIds\":[],\"nodes\":[],\"truncations\":[],\"circularReferences\":[]}")));
        } else {
            int taxonomy = route.contains("case-") ? 0 : 1;
            when(analyses.findById(id))
                    .thenReturn(Optional.of(
                            new MigrationAnalysis(id, engine, "{}", "def:1", "def:2", taxonomy, "{}", "[]", "{}", AT)));
        }
        return id;
    }

    /** The body minus what legitimately differs: the correlation id and the id the caller itself sent. */
    private static String body(ResponseEntity<String> response) {
        return response.getBody()
                .replaceAll("\"requestId\":\"[^\"]*\"", "")
                .replaceAll("[0-9a-f]{8}-[0-9a-f-]{27}", "<id>");
    }

    private static String sameWithoutId(ResponseEntity<String> response) {
        return response.getStatusCode() + " " + body(response);
    }

    @Test
    void aNonAdminGetsTheSameAnswerForAStoredAndAnUnknownId() {
        for (String route : ROUTES) {
            for (String user : new String[] {"viewer", "operator"}) {
                UUID known = store(route, ENGINE);
                ResponseEntity<String> stored = get(user, route, known);
                ResponseEntity<String> unknown = get(user, route, UUID.randomUUID());
                assertThat(stored.getStatusCode()).as(route + user).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(stored.getStatusCode()).as(route + user).isEqualTo(unknown.getStatusCode());
                assertThat(body(stored)).as(route + user).isEqualTo(body(unknown));
            }
        }
    }

    @Test
    void anAdminKeepsOkForAStoredIdAndNotFoundForAnUnknownOne() {
        for (String route : ROUTES) {
            assertThat(get("admin", route, store(route, ENGINE)).getStatusCode())
                    .as(route)
                    .isEqualTo(HttpStatus.OK);
            assertThat(get("admin", route, UUID.randomUUID()).getStatusCode())
                    .as(route)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void anAdminOfAnotherEngineGetsTheUnknownIdAnswer() {
        doReturn(false).when(rbac).hasRoleOn(any(), eq(Role.ADMIN), eq(OTHER));
        for (String route : ROUTES) {
            UUID foreign = store(route, OTHER);
            UUID unknown = UUID.randomUUID();
            Supplier<String> none = () -> sameWithoutId(get("admin", route, unknown));
            assertThat(sameWithoutId(get("admin", route, foreign))).as(route).isEqualTo(none.get());
            assertThat(get("admin", route, foreign).getStatusCode()).as(route).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
