package io.inspector.triage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The normative, versioned error-signature contract (SPECIFICATION §4 Stage 0, R-SEM-03):
 * groups failures by root cause so N instances of one bug render as ONE triage card.
 *
 * Input is whatever the engine gives us — a job row's {@code exceptionMessage} snippet
 * (single line, no class name) or a {@code /exception-stacktrace} body (head line +
 * Caused-by chain). Algorithm v2, proven against the captured golden corpus
 * (backend/src/test/resources/error-signatures/, TEST-STRATEGY §4).
 *
 * <p><b>IDENTITY CONTRACT (v2, #270) — the hash is ALWAYS computed from the SNIPPET.</b>
 * This function is pure over its input; the contract is in who calls it with what. A
 * group's identity hash comes from the job row's {@code exceptionMessage}, which every job
 * carries, costs no extra call, and cannot fail. Stacktrace refinement still runs (bounded
 * by {@code stacktrace-sample-cap}) but is <em>display-only</em>: it supplies the
 * root-cause {@code exceptionClass} shown on the card and NEVER re-keys the group.
 *
 * <p>Why v1 was withdrawn: refinement REPLACED the identity, so whether a cycle happened to
 * refine a group — a function of a largest-first budget and a fetch that can transiently
 * fail — decided its hash. Measured over the captured corpus, the snippet and refined hashes
 * differ on <b>60 of 60</b> entries (the two inputs describe the failure at different
 * levels: {@code "Error while evaluating expression: ${amount % divisor}"} vs
 * {@code "/ by zero"}), so any group flipping refinement state minted a second incident,
 * split its MTTR, and left the retired hash's drill links matching nothing (#270).
 * Dropping {@code exceptionClass} from the hash — the fix the issue originally proposed —
 * would have closed 0 of those 60, because the message differs too.
 *
 * <p>Cost, accepted deliberately: identity is now the engine-reported wrapper message, so
 * two root causes sharing one wrapper text group together. That over-merge already applied
 * to every group past the sample cap under v1; v2 makes it uniform and honest rather than
 * budget-dependent. It is NOT mitigated by folding definition/activity into the hash: acks
 * are already keyed {@code (hash, algoVersion, engineId, definitionKey)} (V15) and the
 * incident ledger is deliberately one row per FLEET-WIDE {@code (hash, algoVersion)} (V18),
 * so that dimension is modelled at the binding layer on purpose.
 *
 * <p>Algorithm:
 *
 * <ol>
 *   <li><b>Class extraction</b> — the head line's {@code fully.qualified.Class: message}
 *       prefix, unwrapped ONE level past a generic wrapper (FlowableException /
 *       ActivitiException / bare RuntimeException) into the first {@code Caused by:}.
 *       JUEL's ELException wrapper embeds the real class in its MESSAGE
 *       ("ELException: java.lang.StringIndexOutOfBoundsException: …" — corpus, all
 *       engine majors), so a wrapper landed on by unwrapping is re-extracted from its
 *       own message text. A message-only snippet yields a null class (the aggregation
 *       refines it from a representative stacktrace).</li>
 *   <li><b>Execution-context tail</b> — Flowable 7.x appends
 *       {@code with Execution[ id '…' ] - definition '…' - activity '…' - parent '…'}
 *       to every message (6.x does not; proven live 2026-07-06). The tail is stripped
 *       so the same root cause hashes identically across engine majors.</li>
 *   <li><b>Sanitization</b> — quoted literals, ISO timestamps, UUIDs, hex runs ≥8 and
 *       digit runs each collapse to {@code #}; whitespace collapses; 200-char hard cap.</li>
 *   <li><b>Hash</b> — SHA-256 over {@code class + "|" + normalizedMessage}: the binding
 *       key for acknowledgments, annotations and playbooks. Bindings persist
 *       {@code algoVersion} alongside; bumping the algorithm flags them
 *       "needs re-binding", never a silent re-bind.</li>
 * </ol>
 */
public final class ErrorSignatureNormalizer {

    /** Bump on ANY behavior change here, regenerate goldens, show the grouping diff (R-SEM-03). */
    public static final int ALGO_VERSION = 2;

    private static final int MESSAGE_CAP = 200;

    /** {@code fully.qualified.SomeException: message} — head lines and embedded root causes. */
    private static final Pattern CLASS_PREFIX =
            Pattern.compile("^([\\p{Alpha}_$][\\w$.]*(?:Exception|Error|Throwable))(?::\\s*(.*))?$", Pattern.DOTALL);

    private static final Pattern CAUSED_BY = Pattern.compile("^Caused by:\\s+(.*)$", Pattern.MULTILINE);

    /** Wrappers by SIMPLE name — package-agnostic on purpose: 6.3 and 6.8+ moved FlowableException. */
    private static final Set<String> WRAPPER_CLASSES =
            Set.of("FlowableException", "ActivitiException", "ELException", "RuntimeException", "Exception");

    /** The 7.x execution-context tail (greedy to end — everything after it is runtime IDs). */
    private static final Pattern EXECUTION_TAIL = Pattern.compile("\\s+with Execution\\[.*$", Pattern.DOTALL);

    // Sanitization rules, applied in order: composite shapes before the digit run that
    // would otherwise shred them; quoted literals first (they often contain the others).
    private static final Pattern QUOTED = Pattern.compile("'[^']*'|\"[^\"]*\"");
    private static final Pattern ISO_TIMESTAMP =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?");
    private static final Pattern UUID_LITERAL =
            Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    /** Hex runs ≥8 — must contain a digit so ordinary words never match. */
    private static final Pattern HEX_RUN = Pattern.compile("\\b(?=[0-9a-fA-F]*\\d)[0-9a-fA-F]{8,}\\b");

    private static final Pattern DIGIT_RUN = Pattern.compile("[-+]?\\d+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ErrorSignatureNormalizer() {}

    /**
     * One normalized signature: the persisted contract triple is
     * {@code (algoVersion, hash, sampleRawMessage)} — the sample lives on the group,
     * not here. {@code exceptionClass} is null for message-only snippets.
     */
    public record ErrorSignature(int algoVersion, String exceptionClass, String normalizedMessage, String hash) {

        /**
         * Adopt a root-cause class resolved from a representative stacktrace, keeping this
         * signature's identity ({@code hash}, {@code algoVersion}, {@code normalizedMessage})
         * untouched — the v2 display-only refinement contract (#270). A null/blank refined
         * class is ignored, so a stacktrace that fails to parse leaves the snippet's own class
         * standing rather than blanking it.
         *
         * <p>Deliberately asymmetric with {@link #normalize}: after this call {@code hash} is
         * NO LONGER {@code sha256(exceptionClass + "|" + normalizedMessage)}. That is the
         * point — the class is a label, the hash is the identity, and only the latter may key
         * a binding.
         */
        public ErrorSignature withDisplayClass(String refinedClass) {
            if (refinedClass == null || refinedClass.isBlank()) {
                return this;
            }
            return new ErrorSignature(algoVersion, refinedClass, normalizedMessage, hash);
        }
    }

    /**
     * Normalize a raw engine payload — a job row's {@code exceptionMessage} or a full
     * {@code exception-stacktrace} body. Null/blank input maps to the stable
     * "(no exception message)" signature, never null: an unparseable payload must not
     * silently vanish from triage (SPEC §10 error-shape doctrine).
     */
    public static ErrorSignature normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return signature(null, "(no exception message)");
        }
        String headLine = raw.lines().findFirst().orElse("").trim();

        String exceptionClass = null;
        String message = headLine;
        Matcher head = CLASS_PREFIX.matcher(headLine);
        if (head.matches()) {
            exceptionClass = head.group(1);
            message = head.group(2) != null ? head.group(2) : "";
        }

        // Unwrap ONE level: a generic wrapper's first Caused-by is the real throwable.
        if (isWrapper(exceptionClass)) {
            Matcher causedBy = CAUSED_BY.matcher(raw);
            if (causedBy.find()) {
                Matcher cause = CLASS_PREFIX.matcher(causedBy.group(1).trim());
                if (cause.matches()) {
                    exceptionClass = cause.group(1);
                    message = cause.group(2) != null ? cause.group(2) : "";
                }
            }
        }
        // A wrapper that embeds its cause in the message (JUEL ELException) re-extracts
        // from the message text — same throwable, so still "one level".
        if (isWrapper(exceptionClass)) {
            Matcher embedded = CLASS_PREFIX.matcher(message.trim());
            if (embedded.matches() && embedded.group(2) != null) {
                exceptionClass = embedded.group(1);
                message = embedded.group(2);
            }
        }
        return signature(exceptionClass, message);
    }

    /** The sanitization pipeline alone — exposed for the rung-1 regex corpus tests. */
    static String sanitize(String message) {
        String m = EXECUTION_TAIL.matcher(message).replaceAll("");
        m = QUOTED.matcher(m).replaceAll("#");
        m = ISO_TIMESTAMP.matcher(m).replaceAll("#");
        m = UUID_LITERAL.matcher(m).replaceAll("#");
        m = HEX_RUN.matcher(m).replaceAll("#");
        m = DIGIT_RUN.matcher(m).replaceAll("#");
        m = WHITESPACE.matcher(m).replaceAll(" ").trim();
        return m.length() > MESSAGE_CAP ? m.substring(0, MESSAGE_CAP) : m;
    }

    private static boolean isWrapper(String exceptionClass) {
        if (exceptionClass == null) {
            return false;
        }
        String simple = exceptionClass.substring(exceptionClass.lastIndexOf('.') + 1);
        return WRAPPER_CLASSES.contains(simple);
    }

    private static ErrorSignature signature(String exceptionClass, String message) {
        String normalized = sanitize(message);
        String keyed = (exceptionClass != null ? exceptionClass : "") + "|" + normalized;
        return new ErrorSignature(ALGO_VERSION, exceptionClass, normalized, sha256(keyed));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e); // spec-mandated algorithm
        }
    }
}
