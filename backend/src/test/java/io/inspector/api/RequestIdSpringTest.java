package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.NoDbTestSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * R-AUD-04 / usability W1#6 (theme T5): every request gets ONE quotable id — inbound
 * {@code X-Request-Id} honored when header-safe, minted otherwise — echoed as a response header
 * on EVERY response and carried in EVERY error body. One error contract (issue #87 — F4): EVERY
 * error path below renders the SAME {@code type/title/status/detail/instance/code/requestId}
 * shape, whether it's a handler-path {@link ActionExceptionHandler} {@code ProblemDetail} (via
 * {@link ProblemDetailRequestIdAdvice}) or the container {@code /error} path — the security
 * 401/403 and the no-handler 404, which used to render Spring's bare
 * {@code {timestamp,status,error,path}} shape — via {@link RequestIdErrorAttributes}.
 *
 * Real-HTTP rung 3 like the sibling suites: MockMvc never performs the sendError → ERROR
 * re-dispatch the container {@code /error} path needs, so only a servlet container proves it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class RequestIdSpringTest {

    private static final String RETRY = "/api/instances/probe-dev/pi-1/actions/retry-job";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    @Test
    void everySuccessResponseCarriesTheRequestIdHeader() {
        ResponseEntity<String> response = as("viewer").getForEntity("/api/engines", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
    }

    @Test
    void inboundRequestIdIsHonoredEndToEnd() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", "gateway-4711.a");
        ResponseEntity<String> response =
                as("viewer").exchange("/api/engines", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("gateway-4711.a");
    }

    @Test
    void unsafeInboundRequestIdIsReplacedNotReflected() {
        // Header/log-safe charset only — anything else is attacker-controlled noise (log
        // injection, header reflection) and gets a fresh UUID instead of an echo.
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", "spaced out & weird");
        ResponseEntity<String> response =
                as("viewer").exchange("/api/engines", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        String echoed = response.getHeaders().getFirst("X-Request-Id");
        assertThat(echoed).isNotBlank();
        assertThat(echoed).isNotEqualTo("spaced out & weird");

        HttpHeaders overlong = new HttpHeaders();
        overlong.set("X-Request-Id", "x".repeat(65));
        String echoedLong = as("viewer")
                .exchange("/api/engines", HttpMethod.GET, new HttpEntity<>(overlong), String.class)
                .getHeaders()
                .getFirst("X-Request-Id");
        assertThat(echoedLong).isNotEqualTo("x".repeat(65));
    }

    @Test
    void problemDetailErrorsCarryTheRequestIdProperty() throws Exception {
        // A guard refusal (missing jobId) renders through ActionExceptionHandler — the
        // ProblemDetail body must carry the same id the header does.
        ResponseEntity<String> response = as("responder").postForEntity(RETRY, Map.of(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        JsonNode problem = mapper.readTree(response.getBody());
        assertThat(problem.path("code").asText()).isEqualTo("missing-field");
        String headerId = response.getHeaders().getFirst("X-Request-Id");
        assertThat(problem.path("requestId").asText()).isNotBlank().isEqualTo(headerId);
    }

    @Test
    void containerPath403CarriesTheSameProblemDetailShapeAndRequestId() throws Exception {
        // The security 403 (@PreAuthorize denial → sendError → /error) now renders the SAME
        // ProblemDetail+code shape as a handler-path error (RequestIdErrorAttributes) — no
        // longer Spring's bare {timestamp,status,error,path} (RUN-REPORT theme 5 / issue #87).
        ResponseEntity<String> response = as("viewer").postForEntity(RETRY, Map.of("jobId", "j1"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.path("status").asInt()).isEqualTo(403);
        assertThat(body.path("code").asText()).isEqualTo("forbidden");
        assertThat(body.path("title").asText()).isNotBlank();
        assertThat(body.path("detail").asText()).isNotBlank();
        String headerId = response.getHeaders().getFirst("X-Request-Id");
        assertThat(body.path("requestId").asText()).isNotBlank().isEqualTo(headerId);
    }

    @Test
    void searchBadFilterErrorCarriesTheSameProblemDetailShapeAndRequestId() throws Exception {
        // Bad sortBy / unparseable window: previously SearchController's own ad-hoc
        // {"error":…} map handler, now the same global ProblemDetail+code as every other
        // IllegalArgumentException (issue #87 — F4, one error contract).
        ResponseEntity<String> response =
                as("viewer").postForEntity("/api/search", Map.of("sortBy", "bogus"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.path("code").asText()).isEqualTo("bad-request");
        assertThat(body.path("detail").asText()).contains("sortBy");
        String headerId = response.getHeaders().getFirst("X-Request-Id");
        assertThat(body.path("requestId").asText()).isNotBlank().isEqualTo(headerId);
    }

    @Test
    void noHandlerFound404CarriesTheSameProblemDetailShapeAndRequestId() throws Exception {
        ResponseEntity<String> response = as("viewer").getForEntity("/api/definitely-not-a-route", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.path("status").asInt()).isEqualTo(404);
        assertThat(body.path("code").asText()).isEqualTo("not-found");
        String headerId = response.getHeaders().getFirst("X-Request-Id");
        assertThat(body.path("requestId").asText()).isNotBlank().isEqualTo(headerId);
    }
}
