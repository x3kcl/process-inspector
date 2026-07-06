package io.inspector.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.triage.ErrorSignatureNormalizer.ErrorSignature;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Rung 1 golden gate (TEST-STRATEGY §4, R-SEM-03): the corpus files are REAL payloads
 * captured from the live compose matrix (docker/capture-error-corpus.py — never
 * hand-written). CI asserts zero unparseable entries and the exact kind→class mapping;
 * a normalizer change must bump ALGO_VERSION, re-run the capture, and show the grouping
 * diff in the PR. Image bumps re-capture before capability sign-off.
 */
class ErrorSignatureGoldenCorpusTest {

    /** kind → expected root-cause class SIMPLE name (packages moved across majors). */
    private static final Map<String, String> EXPECTED_CLASS = Map.of(
            "arithmetic", "ArithmeticException",
            "retrying-arithmetic", "ArithmeticException",
            "string-index", "StringIndexOutOfBoundsException",
            "missing-property", "PropertyNotFoundException",
            "method-not-found", "MethodNotFoundException");

    private record Entry(String kind, String engineVersion, String exceptionMessage, String stacktrace) {}

    private static List<Entry> corpus(String major) throws Exception {
        JsonNode root = new ObjectMapper()
                .readTree(ErrorSignatureGoldenCorpusTest.class.getResourceAsStream(
                        "/error-signatures/" + major + "/corpus.json"));
        List<Entry> entries = new ArrayList<>();
        root.get("entries")
                .forEach(e -> entries.add(new Entry(
                        e.get("kind").asText(),
                        e.get("engineVersion").asText(),
                        e.get("exceptionMessage").asText(),
                        e.get("stacktrace").asText())));
        assertThat(entries).as("corpus floor, TEST-STRATEGY §4").hasSizeGreaterThanOrEqualTo(30);
        return entries;
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.x", "7.x"})
    void zeroUnparseableAndExactClassMapping(String major) throws Exception {
        for (Entry e : corpus(major)) {
            ErrorSignature fromSnippet = ErrorSignatureNormalizer.normalize(e.exceptionMessage());
            assertThat(fromSnippet.normalizedMessage())
                    .as("%s/%s snippet must normalize", major, e.kind())
                    .isNotBlank();
            assertThat(fromSnippet.hash()).hasSize(64);

            ErrorSignature fromTrace = ErrorSignatureNormalizer.normalize(e.stacktrace());
            assertThat(fromTrace.exceptionClass())
                    .as(
                            "%s/%s (%s) stacktrace must resolve the ROOT class — a silent parser"
                                    + " failure degrades every failure into one unparseable group",
                            major, e.kind(), e.engineVersion())
                    .isNotNull()
                    .endsWith(EXPECTED_CLASS.get(e.kind()));
        }
    }

    /** N instances of one bug = ONE signature: the whole point of the normalizer. */
    @ParameterizedTest
    @ValueSource(strings = {"6.x", "7.x"})
    void instancesOfOneKindCollapseToOneSignaturePerEngineVersion(String major) throws Exception {
        Map<List<String>, Set<String>> hashesPerKindAndVersion = corpus(major).stream()
                .collect(Collectors.groupingBy(
                        e -> List.of(e.kind(), e.engineVersion()),
                        Collectors.mapping(
                                e -> ErrorSignatureNormalizer.normalize(e.stacktrace())
                                        .hash(),
                                Collectors.toSet())));
        hashesPerKindAndVersion.forEach((kindAndVersion, hashes) -> assertThat(hashes)
                .as("all %s instances on %s must share one signature", kindAndVersion.get(0), kindAndVersion.get(1))
                .hasSize(1));
    }

    /**
     * The arithmetic root cause must hash IDENTICALLY on 6.3.1, 6.8 and 7.1 — the 7.x
     * execution-context tail and per-instance IDs are exactly what the sanitizer exists
     * to neutralize (SPEC §10: error-shape drift across the Jakarta baseline).
     */
    @ParameterizedTest
    @ValueSource(strings = {"arithmetic"})
    void rootCauseSignatureConvergesAcrossEngineMajors(String kind) throws Exception {
        Set<String> hashes = new HashSet<>();
        for (String major : List.of("6.x", "7.x")) {
            corpus(major).stream()
                    .filter(e -> e.kind().equals(kind))
                    .map(e -> ErrorSignatureNormalizer.normalize(e.stacktrace()).hash())
                    .forEach(hashes::add);
        }
        assertThat(hashes).hasSize(1);
    }
}
