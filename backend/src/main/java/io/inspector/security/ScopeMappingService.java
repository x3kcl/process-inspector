package io.inspector.security;

import io.inspector.audit.AuditService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The BFF-owned group→scope mapping (R-SAFE-12, ADR-003): OIDC delivers identity + coarse
 * groups ONLY; which (role, engineId, tenantId) tuples a group carries is decided here,
 * from a separately mounted, hot-reloaded YAML file:
 *
 * <pre>
 * groups:
 *   flowable-admins:
 *     - role: ADMIN
 *       engine-id: "*"
 *       tenant-id: "*"
 *   orders-l1:
 *     - role: RESPONDER
 *       engine-id: orders-prod
 * </pre>
 *
 * The file is re-read on a ≤60s TTL; a content-hash change is logged as a config event.
 * Resolution happens at CHECK time (not login time) so a mid-incident grant takes effect
 * on live sessions within the TTL — no pipeline run, no restart, no re-login.
 */
@Service
public class ScopeMappingService {

    private static final Logger log = LoggerFactory.getLogger(ScopeMappingService.class);

    private final SecurityProperties props;
    private final Clock clock;
    private final AuditService audit;

    // AtomicReference (not a bare volatile) so the out-of-lock adopt/roll-back after a reload can
    // compare-and-set against the exact interim this thread published — a slower thread whose audit
    // outran the TTL can never clobber a concurrent adopt, and a roll-back can never pair one
    // snapshot's byGroup with another's contentHash (Gemini S1).
    private final AtomicReference<Snapshot> snapshot =
            new AtomicReference<>(new Snapshot(Map.of(), null, Instant.EPOCH));

    private record Snapshot(Map<String, List<ScopeGrant>> byGroup, String contentHash, Instant loadedAt) {
        Snapshot withLoadedAt(Instant t) {
            return new Snapshot(byGroup, contentHash, t);
        }
    }

    public ScopeMappingService(SecurityProperties props, Clock clock, AuditService audit) {
        this.props = props;
        this.clock = clock;
        this.audit = audit;
    }

    /** Scoped grants for a set of IdP groups, from the freshest mapping (TTL-bounded). */
    public Set<ScopeGrant> grantsForGroups(Collection<String> groups) {
        Map<String, List<ScopeGrant>> mapping = current().byGroup();
        Set<ScopeGrant> grants = new LinkedHashSet<>();
        for (String group : groups) {
            grants.addAll(mapping.getOrDefault(group, List.of()));
        }
        return grants;
    }

    /** Distinct roles the groups grant anywhere — the coarse ROLE_* authorities for the session. */
    public Set<Role> rolesForGroups(Collection<String> groups) {
        Set<Role> roles = new LinkedHashSet<>();
        for (ScopeGrant grant : grantsForGroups(groups)) {
            roles.add(grant.role());
        }
        return roles;
    }

    private Snapshot current() {
        long ttl = props.reloadTtlSOrDefault();
        Snapshot snap = snapshot.get();
        if (Duration.between(snap.loadedAt(), clock.instant()).getSeconds() < ttl) {
            return snap;
        }

        Change change;
        synchronized (this) {
            snap = snapshot.get();
            Instant now = clock.instant();
            if (Duration.between(snap.loadedAt(), now).getSeconds() < ttl) {
                return snap; // another thread reloaded while we waited for the lock
            }
            change = readMapping(snap, now);
            // Publish the interim snapshot. For a genuine CHANGE this KEEPS the previous byGroup
            // but CLAIMS the new content hash + a fresh loadedAt, so concurrent callers take the
            // fast path (serve the previous mapping, never block on the audit DB below) and this
            // reload is not emitted twice.
            snapshot.set(change.interim());
        }
        if (!change.needsAudit()) {
            return snapshot.get(); // no change, or empty/unreadable file — nothing to record
        }

        // R-SAFE-12 / R-AUD-10: a content change is a security-relevant config event — record it
        // OUTSIDE the lock (D1a: audit-DB latency must never wedge auth resolution). The acting
        // human is NOT the actor — the mapping changed on disk, not because that user did
        // anything — so actor = "system".
        try {
            audit.recordConfigEvent("config-scope-mapping-reload", "system", change.parsedOk(), change.auditPayload());
        } catch (RuntimeException e) {
            // Fail-to-previous (R-AUD-10): an unauditable grant change never goes live. Roll the
            // claimed hash back — to the interim's OWN byGroup, never another thread's, so byGroup
            // and contentHash stay consistent — and CAS so a concurrent adopt is never clobbered.
            log.error(
                    "scope-mapping change (sha256={}) NOT adopted — config event unrecordable, keeping"
                            + " previous mapping (retries next TTL)",
                    change.rawHash(),
                    e);
            Snapshot interim = change.interim();
            snapshot.compareAndSet(interim, new Snapshot(interim.byGroup(), change.previousHash(), interim.loadedAt()));
            return snapshot.get();
        }

        // Audited. Adopt the new mapping when the reload PARSED OK; a broken reload stays on the
        // previous mapping (already the interim) with the failure now on the ledger. CAS against our
        // interim so a newer concurrent reload is never overwritten by this staler adopt.
        if (change.parsedOk()) {
            log.info(
                    "scope-mapping reloaded from {} (sha256={}, {} groups) — audited",
                    props.scopeMappingFile(),
                    change.rawHash(),
                    change.byGroup().size());
            snapshot.compareAndSet(change.interim(), new Snapshot(change.byGroup(), change.rawHash(), clock.instant()));
        }
        return snapshot.get();
    }

    /** The outcome of reading the mapping file — pure (no audit, no adoption of a change). */
    private record Change(
            boolean parsedOk,
            Map<String, List<ScopeGrant>> byGroup,
            String rawHash,
            String previousHash,
            Snapshot interim,
            Map<String, Object> auditPayload) {
        boolean needsAudit() {
            return auditPayload != null;
        }

        static Change none(Snapshot interim) {
            return new Change(true, Map.of(), null, null, interim, null);
        }
    }

    private Change readMapping(Snapshot previous, Instant now) {
        String file = props.scopeMappingFile();
        if (file == null || file.isBlank()) {
            return Change.none(new Snapshot(Map.of(), null, now));
        }
        byte[] bytes;
        String hash;
        try {
            bytes = Files.readAllBytes(Path.of(file));
            hash = sha256(bytes);
        } catch (IOException | RuntimeException e) {
            // Cannot even read the file (vanished / permissions) — keep previous, refresh the TTL.
            // Not a content change worth a ledger row; log loudly for the operator.
            log.error("scope-mapping unreadable at {} — keeping previous mapping: {}", file, e.toString());
            return Change.none(previous.withLoadedAt(now));
        }
        if (hash.equals(previous.contentHash())) {
            return Change.none(previous.withLoadedAt(now)); // no content change
        }
        // Content changed: the interim KEEPS the previous byGroup but claims the new hash (fail-to-
        // previous — the new mapping is not live until audited & parsed).
        Snapshot interim = new Snapshot(previous.byGroup(), hash, now);
        try {
            Map<String, List<ScopeGrant>> parsed = parse(bytes);
            int grants = parsed.values().stream().mapToInt(List::size).sum();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("file", file);
            payload.put("sha256", hash);
            payload.put("previousSha256", previous.contentHash()); // null on the boot baseline
            payload.put("groupCount", parsed.size());
            payload.put("grantCount", grants);
            return new Change(true, parsed, hash, previous.contentHash(), interim, payload);
        } catch (RuntimeException e) {
            // Changed but unparseable — audit the FAILED reload with a sanitized error (never the
            // raw YAML fragment, which echoes group/grant names — DP-minimization), keep previous.
            log.error(
                    "scope-mapping reload from {} failed to parse — keeping previous mapping: {}", file, e.toString());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("file", file);
            payload.put("errorClass", e.getClass().getSimpleName());
            payload.put("error", sanitize(e.getMessage()));
            return new Change(false, previous.byGroup(), hash, previous.contentHash(), interim, payload);
        }
    }

    /** CR/LF-stripped, length-capped exception message for the failed-reload ledger row (D1c). */
    private static String sanitize(String message) {
        if (message == null) {
            return null;
        }
        String oneLine = message.replaceAll("[\\r\\n]+", " ").strip();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) : oneLine;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<ScopeGrant>> parse(byte[] bytes) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new IllegalArgumentException("scope-mapping root must be a map with a 'groups' key");
        }
        Object groupsNode = ((Map<String, Object>) rootMap).get("groups");
        if (!(groupsNode instanceof Map<?, ?> groups)) {
            throw new IllegalArgumentException("scope-mapping must contain a 'groups' map");
        }
        Map<String, List<ScopeGrant>> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : groups.entrySet()) {
            String group = String.valueOf(entry.getKey());
            List<Map<String, Object>> grantNodes = (List<Map<String, Object>>) entry.getValue();
            List<ScopeGrant> grants = grantNodes == null
                    ? List.of()
                    : grantNodes.stream().map(ScopeMappingService::toGrant).toList();
            result.put(group, grants);
        }
        return Map.copyOf(result);
    }

    private static ScopeGrant toGrant(Map<String, Object> node) {
        Object role = node.get("role");
        if (role == null) {
            throw new IllegalArgumentException("scope-mapping grant without a role");
        }
        String engineId = node.get("engine-id") != null ? String.valueOf(node.get("engine-id")) : ScopeGrant.ANY;
        String tenantId = node.get("tenant-id") != null ? String.valueOf(node.get("tenant-id")) : ScopeGrant.ANY;
        return new ScopeGrant(Role.valueOf(String.valueOf(role)), engineId, tenantId);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
