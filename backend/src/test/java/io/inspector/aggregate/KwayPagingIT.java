package io.inspector.aggregate;

import io.inspector.support.NoDbTestSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Deep-paging cursor chain against REAL flowable-rest 6.8 (engine-a on :8081). See
 * {@link AbstractKwayPagingIT}; the 7.1 sibling is {@link Kway7IT}.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml up -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-kway")
@Import(NoDbTestSupport.class)
class KwayPagingIT extends AbstractKwayPagingIT {

    @Override
    protected String engineUrl() {
        return "http://localhost:" + System.getenv().getOrDefault("PI_ENGINE_A_PORT", "8081")
                + "/flowable-rest/service";
    }

    @Override
    protected String scrollEngineId() {
        return "kway-scroll";
    }

    @Override
    protected String cappedEngineId() {
        return "kway-capped";
    }

    @Override
    protected String downEngineId() {
        return "kway-down";
    }
}
