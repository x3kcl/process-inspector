package io.inspector.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The audit golden master's write path (SPEC §9). Two rules dominate everything here:
 *
 * <ul>
 *   <li><b>Fail-closed (R-AUD-01):</b> {@link #beginPending} runs BEFORE the engine call;
 *       ANY failure to commit the PENDING row aborts the mutation with
 *       {@link AuditUnavailableException} — the engine never sees an unauditable action.
 *   <li><b>No dual-write lying (R-SEM-18):</b> {@link #close} moves the row
 *       {@code PENDING → ok|failed|unknown}. When the engine call succeeded but close
 *       fails, the caller reports "Action dispatched — outcome verification failed" —
 *       never a generic 500 — and the reconciler sweeps the stale PENDING row to unknown.
 * </ul>
 *
 * Inserts are single-writer serialized for the tamper-evidence hash chain: each row's
 * {@code chain_hash} covers its immutable insert-time fields plus the previous row's
 * hash (mutable outcome columns are excluded — they change in place by design). The BFF
 * is a single instance (ARCH §5), so a process-local lock suffices.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final String CHAIN_GENESIS = "genesis";

    /**
     * Secret-name denylist (R-AUD-03): variable/field names matching any of these have
     * their VALUES replaced by «redacted» in audit payloads. Names stay — the skeleton
     * is the accountability record.
     */
    private static final List<String> SECRET_NAME_DENYLIST =
            List.of("password", "passwd", "secret", "token", "credential", "apikey", "api_key", "api-key");

    public static final String REDACTED = "«redacted»";

    private final AuditEntryRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Object chainLock = new Object();

    public AuditService(AuditEntryRepository repository, ObjectMapper mapper, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * Insert the PENDING row — the fail-closed gate. The returned entry is already
     * committed; only after this may the caller touch the engine.
     *
     * @throws AuditUnavailableException on ANY persistence failure (the mutation must not proceed)
     */
    public AuditEntry beginPending(
            String actor,
            String engineId,
            String tenantId,
            String instanceId,
            String action,
            String reason,
            String ticketId,
            Map<String, Object> payload) {
        String payloadJson = toJson(redact(payload));
        try {
            synchronized (chainLock) {
                String previousHash = repository
                        .findTopByOrderBySeqDesc()
                        .map(AuditEntry::getChainHash)
                        .orElse(CHAIN_GENESIS);
                AuditEntry entry = new AuditEntry(
                        UUID.randomUUID(),
                        UUID.randomUUID().toString(),
                        actor,
                        clock.instant(),
                        engineId,
                        tenantId,
                        instanceId,
                        action,
                        reason,
                        ticketId,
                        payloadJson,
                        false);
                entry.setChainHash(chainHash(previousHash, entry));
                return repository.saveAndFlush(entry);
            }
        } catch (RuntimeException e) {
            throw new AuditUnavailableException(e);
        }
    }

    /**
     * Close the PENDING row. {@code engineSucceeded} decides how a close failure is
     * reported: after a successful engine call it becomes the specialized
     * dispatched-but-unverified error; after a failed one the original engine error must
     * dominate (nothing state-changing happened), so we only log.
     */
    public void close(
            AuditEntry entry, AuditOutcome outcome, Integer httpStatus, String snippet, boolean engineSucceeded) {
        try {
            String bounded = truncateToBytes(snippet, AuditEntry.SNIPPET_MAX_BYTES);
            entry.close(outcome, httpStatus, bounded, bounded != null && !bounded.equals(snippet));
            repository.saveAndFlush(entry);
        } catch (RuntimeException e) {
            if (engineSucceeded) {
                throw new OutcomeVerificationFailedException(entry.getId(), e);
            }
            log.error(
                    "audit close failed AFTER a refused/failed engine call (audit id {}, outcome {}) — row stays"
                            + " PENDING for the reconciler: {}",
                    entry.getId(),
                    outcome,
                    e.toString());
        }
    }

    /* ---------- payload hygiene ---------- */

    /** Replace values whose KEY matches the secret-name denylist, recursively. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> redact(Map<String, Object> payload) {
        if (payload == null) {
            return Map.of();
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (isSecretName(entry.getKey())) {
                clean.put(entry.getKey(), REDACTED);
            } else if (entry.getValue() instanceof Map<?, ?> nested) {
                clean.put(entry.getKey(), redact((Map<String, Object>) nested));
            } else {
                clean.put(entry.getKey(), entry.getValue());
            }
        }
        return clean;
    }

    public static boolean isSecretName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return SECRET_NAME_DENYLIST.stream().anyMatch(lower::contains);
    }

    static String truncateToBytes(String value, int maxBytes) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return value;
        }
        // Accumulate whole code points up to the byte cap (never split one).
        int bytes = 0;
        int i = 0;
        while (i < value.length()) {
            int codePoint = value.codePointAt(i);
            int width = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + width > maxBytes) {
                break;
            }
            bytes += width;
            i += Character.charCount(codePoint);
        }
        return value.substring(0, i);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Payload serialization must never block the audit path; record the failure.
            return "{\"_unserializable\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }

    private static String chainHash(String previousHash, AuditEntry e) {
        String material = String.join(
                "|",
                previousHash,
                e.getId().toString(),
                e.getCorrelationId(),
                e.getActor(),
                e.getTs().toString(),
                e.getEngineId(),
                String.valueOf(e.getTenantId()),
                String.valueOf(e.getInstanceId()),
                e.getAction(),
                String.valueOf(e.getReason()),
                String.valueOf(e.getTicketId()),
                String.valueOf(e.getPayload()));
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
