package io.inspector.triage;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.triage.ErrorSignatureNormalizer.ErrorSignature;
import org.junit.jupiter.api.Test;

/**
 * Rung 1: the R-SEM-03 signature contract over hand-picked shapes. The REAL engine
 * payloads live in {@link ErrorSignatureGoldenCorpusTest} — this class pins each
 * sanitization rule and the unwrap semantics in isolation.
 */
class ErrorSignatureNormalizerTest {

    /* ---------- sanitization rules (Task 1 regex corpus) ---------- */

    @Test
    void stripsUuids() {
        assertThat(ErrorSignatureNormalizer.sanitize("execution 0c063fea-791b-11f1-8340-021e3f40687e not found"))
                .isEqualTo("execution # not found");
    }

    @Test
    void stripsHexRunsOfEightOrMore() {
        assertThat(ErrorSignatureNormalizer.sanitize("tx deadbeef42 aborted")).isEqualTo("tx # aborted");
        // pure-alpha words are never hex runs; short hex stays (could be a real word)
        assertThat(ErrorSignatureNormalizer.sanitize("facade decoded cafe")).isEqualTo("facade decoded cafe");
    }

    @Test
    void stripsDigitRunsIncludingSign() {
        assertThat(ErrorSignatureNormalizer.sanitize("String index out of range: -96"))
                .isEqualTo("String index out of range: #");
        assertThat(ErrorSignatureNormalizer.sanitize("row 42 of 17000")).isEqualTo("row # of #");
    }

    @Test
    void stripsQuotedLiterals() {
        assertThat(ErrorSignatureNormalizer.sanitize("Cannot resolve identifier 'ghost'"))
                .isEqualTo("Cannot resolve identifier #");
        assertThat(ErrorSignatureNormalizer.sanitize("key \"order-77\" missing"))
                .isEqualTo("key # missing");
    }

    @Test
    void stripsIsoTimestampsAsOneToken() {
        assertThat(ErrorSignatureNormalizer.sanitize("due 2026-07-06T09:14:45.798+00:00 passed"))
                .isEqualTo("due # passed");
        assertThat(ErrorSignatureNormalizer.sanitize("at 2026-07-06T09:14:45Z")).isEqualTo("at #");
    }

    @Test
    void stripsFlowable7ExecutionContextTail() {
        String seven = "Error while evaluating expression: ${amount % divisor} with Execution[ id"
                + " '733e7f1f-7931-11f1-aa6f-964226a8ba5c' ] - definition 'demoFailingPayment:2:d22d…' -"
                + " activity 'chargePayment' - parent '733e5809-7931-11f1-aa6f-964226a8ba5c'";
        assertThat(ErrorSignatureNormalizer.sanitize(seven))
                .isEqualTo("Error while evaluating expression: ${amount % divisor}");
    }

    @Test
    void collapsesWhitespaceAndCapsAt200() {
        assertThat(ErrorSignatureNormalizer.sanitize("a\t b\n  c")).isEqualTo("a b c");
        assertThat(ErrorSignatureNormalizer.sanitize("x".repeat(500))).hasSize(200);
    }

    /* ---------- class extraction & unwrap ---------- */

    @Test
    void snippetWithoutClassPrefixKeepsMessageAndNullClass() {
        ErrorSignature sig =
                ErrorSignatureNormalizer.normalize("Error while evaluating expression: ${amount % divisor}");
        assertThat(sig.exceptionClass()).isNull();
        assertThat(sig.normalizedMessage()).isEqualTo("Error while evaluating expression: ${amount % divisor}");
        assertThat(sig.algoVersion()).isEqualTo(ErrorSignatureNormalizer.ALGO_VERSION);
    }

    @Test
    void unwrapsGenericFlowableExceptionOneLevel() {
        String trace = """
                org.flowable.common.engine.api.FlowableException: Error while evaluating expression: ${amount % divisor}
                \tat org.flowable.common.engine.impl.el.JuelExpression.getValue(JuelExpression.java:60)
                Caused by: java.lang.ArithmeticException: / by zero
                \tat org.flowable.common.engine.impl.el.JuelExpression.getValue(JuelExpression.java:60)
                """;
        ErrorSignature sig = ErrorSignatureNormalizer.normalize(trace);
        assertThat(sig.exceptionClass()).isEqualTo("java.lang.ArithmeticException");
        assertThat(sig.normalizedMessage()).isEqualTo("/ by zero");
    }

    @Test
    void nonWrapperHeadIsNeverUnwrapped() {
        String trace = """
                java.lang.IllegalStateException: connector down
                Caused by: java.net.ConnectException: Connection refused
                """;
        assertThat(ErrorSignatureNormalizer.normalize(trace).exceptionClass())
                .isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    void wrapperLandedOnByUnwrapReExtractsFromItsEmbeddedMessage() {
        // JUEL ELException embeds the real class in its message (corpus, all engine majors).
        String trace = """
                org.flowable.common.engine.api.FlowableException: Error while evaluating expression: ${orderRef.substring(100)}
                Caused by: org.flowable.common.engine.impl.javax.el.ELException: java.lang.StringIndexOutOfBoundsException: String index out of range: -96
                \t... 23 more
                Caused by: java.lang.StringIndexOutOfBoundsException: String index out of range: -96
                """;
        ErrorSignature sig = ErrorSignatureNormalizer.normalize(trace);
        assertThat(sig.exceptionClass()).isEqualTo("java.lang.StringIndexOutOfBoundsException");
        assertThat(sig.normalizedMessage()).isEqualTo("String index out of range: #");
    }

    @Test
    void nullAndBlankMapToTheStableNoMessageSignature() {
        ErrorSignature fromNull = ErrorSignatureNormalizer.normalize(null);
        ErrorSignature fromBlank = ErrorSignatureNormalizer.normalize("  ");
        assertThat(fromNull.normalizedMessage()).isEqualTo("(no exception message)");
        assertThat(fromNull.hash()).isEqualTo(fromBlank.hash());
    }

    @Test
    void hashBindsClassAndNormalizedMessage() {
        ErrorSignature a = ErrorSignatureNormalizer.normalize("java.lang.ArithmeticException: / by zero");
        ErrorSignature b = ErrorSignatureNormalizer.normalize("java.lang.ArithmeticException: / by zero");
        ErrorSignature c = ErrorSignatureNormalizer.normalize("java.lang.NullPointerException: / by zero");
        assertThat(a.hash()).isEqualTo(b.hash()).hasSize(64).isNotEqualTo(c.hash());
    }

    @Test
    void instanceNoiseCollapsesToOneSignature() {
        ErrorSignature a = ErrorSignatureNormalizer.normalize(
                "java.lang.StringIndexOutOfBoundsException: Range [100, 4) out of bounds for length 4");
        ErrorSignature b = ErrorSignatureNormalizer.normalize(
                "java.lang.StringIndexOutOfBoundsException: Range [100, 7) out of bounds for length 7");
        assertThat(a.hash()).isEqualTo(b.hash());
    }
}
