package io.inspector.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document served at /v3/api-docs is the frontend's type contract (R-SEM-15):
 * `npm run gen:api` feeds it through openapi-typescript into frontend/src/api/schema.d.ts,
 * which is committed. The info block is pinned (fixed version, no timestamps) and springdoc
 * key-ordering is enabled in application.yml so regeneration is deterministic — a diff in
 * schema.d.ts means the API surface actually changed.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI processInspectorOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Process Inspector BFF")
                        .description(
                                "Aggregating BFF over the configured Flowable engines. All engine access is fanned out and merged here; clients never talk to an engine directly.")
                        .version("v1"));
    }
}
