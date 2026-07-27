package io.inspector.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The sibling-context derivation, pinned per DEPLOYMENT LAYOUT. Every case here is a URL an engine
 * really publishes, not a synthetic string: the war layout is what {@code flowable/flowable-rest}
 * serves, the Boot layout is what an application embedding {@code flowable-spring-boot-starter}
 * serves (the flap engine on hp02).
 */
class EngineApiContextsTest {

    @Nested
    @DisplayName("standalone flowable-rest war — the /service context")
    class WarLayout {

        @Test
        void cmmnApiReplacesService() {
            assertThat(EngineApiContexts.cmmnApi("http://engine-a:8080/flowable-rest/service"))
                    .isEqualTo("http://engine-a:8080/flowable-rest/cmmn-api");
        }

        @Test
        void externalJobApiReplacesService() {
            assertThat(EngineApiContexts.externalJobApi("http://engine-a:8080/flowable-rest/service"))
                    .isEqualTo("http://engine-a:8080/flowable-rest/external-job-api");
        }
    }

    @Nested
    @DisplayName("embedded Spring Boot engine — the /process-api context")
    class BootLayout {

        /**
         * The regression this class exists for: appending would give
         * {@code …/process-api/cmmn-api}, which 404s on every Boot engine — the Case Inspector
         * would read "no CMMN capability" on an engine that has one.
         */
        @Test
        void cmmnApiReplacesProcessApi() {
            assertThat(EngineApiContexts.cmmnApi("http://flap:8080/process-api"))
                    .isEqualTo("http://flap:8080/cmmn-api");
        }

        @Test
        void externalJobApiReplacesProcessApi() {
            assertThat(EngineApiContexts.externalJobApi("http://flap:8080/process-api"))
                    .isEqualTo("http://flap:8080/external-job-api");
        }

        @Test
        void aServletContextPrefixIsPreserved() {
            assertThat(EngineApiContexts.cmmnApi("https://flap.example.com/flap/process-api"))
                    .isEqualTo("https://flap.example.com/flap/cmmn-api");
        }

        @Test
        void caseInThePathSegmentDoesNotMatter() {
            assertThat(EngineApiContexts.cmmnApi("http://flap:8080/Process-API"))
                    .isEqualTo("http://flap:8080/cmmn-api");
        }
    }

    @Nested
    @DisplayName("layouts the derivation does not know")
    class Fallback {

        /** Appending is the safe guess: a wrong 404 on an optional lane, never another context. */
        @Test
        void anUnknownSuffixIsAppendedTo() {
            assertThat(EngineApiContexts.cmmnApi("http://engine:8080/custom-api"))
                    .isEqualTo("http://engine:8080/custom-api/cmmn-api");
        }

        @Test
        void anOriginWithNoPathIsAppendedTo() {
            assertThat(EngineApiContexts.externalJobApi("http://engine:8080"))
                    .isEqualTo("http://engine:8080/external-job-api");
        }
    }

    @Nested
    @DisplayName("input hygiene")
    class Hygiene {

        @Test
        void aTrailingSlashIsTheSameBase() {
            assertThat(EngineApiContexts.cmmnApi("http://flap:8080/process-api/"))
                    .isEqualTo("http://flap:8080/cmmn-api");
            assertThat(EngineApiContexts.cmmnApi("http://engine-a:8080/flowable-rest/service/"))
                    .isEqualTo("http://engine-a:8080/flowable-rest/cmmn-api");
        }

        /** The registry passes a bare PATH (not a whole URL) through the same helper. */
        @Test
        void aBarePathWorksTheSameWay() {
            assertThat(EngineApiContexts.sibling("/process-api", "/cmmn-api")).isEqualTo("/cmmn-api");
            assertThat(EngineApiContexts.sibling("", "/cmmn-api")).isEqualTo("/cmmn-api");
        }

        /**
         * Only a WHOLE trailing SEGMENT is a layout match. A path merely ending in those letters
         * ({@code …/my-process-api}) is somebody else's context: it falls back to appending rather
         * than eating half a segment name.
         */
        @Test
        void aPartialSegmentMatchIsNotALayout() {
            assertThat(EngineApiContexts.cmmnApi("http://engine:8080/my-process-api"))
                    .isEqualTo("http://engine:8080/my-process-api/cmmn-api");
        }
    }
}
