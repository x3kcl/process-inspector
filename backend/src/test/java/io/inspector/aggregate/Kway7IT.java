package io.inspector.aggregate;

import io.inspector.support.NoDbTestSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Rung 4, cross-version: the deep-paging cursor chain on Flowable 7.1 (engine-7 on :8083), which
 * emits {@code Z}-form timestamps where 6.8 emits {@code +00:00} (the S0 spike finding that made the
 * R-SEM-23 Instant-parse load-bearing). Same assertions as 6.8 ({@link AbstractKwayPagingIT}).
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml --profile flowable-7 up -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_7_PASSWORD=test")
@ActiveProfiles("it7-kway")
@Import(NoDbTestSupport.class)
class Kway7IT extends AbstractKwayPagingIT {

    @Override
    protected String engineUrl() {
        return "http://localhost:" + System.getenv().getOrDefault("PI_ENGINE_7_PORT", "8083")
                + "/flowable-rest/service";
    }

    @Override
    protected String scrollEngineId() {
        return "kway7-scroll";
    }

    @Override
    protected String cappedEngineId() {
        return "kway7-capped";
    }

    @Override
    protected String downEngineId() {
        return "kway7-down";
    }
}
