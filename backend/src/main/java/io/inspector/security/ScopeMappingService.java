package io.inspector.security;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private volatile Snapshot snapshot = new Snapshot(Map.of(), null, Instant.EPOCH);

    private record Snapshot(Map<String, List<ScopeGrant>> byGroup, String contentHash, Instant loadedAt) {}

    public ScopeMappingService(SecurityProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
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
        Snapshot snap = this.snapshot;
        Instant now = clock.instant();
        if (Duration.between(snap.loadedAt(), now).getSeconds() < props.reloadTtlSOrDefault()) {
            return snap;
        }
        synchronized (this) {
            snap = this.snapshot;
            if (Duration.between(snap.loadedAt(), now).getSeconds() < props.reloadTtlSOrDefault()) {
                return snap;
            }
            this.snapshot = load(snap);
            return this.snapshot;
        }
    }

    private Snapshot load(Snapshot previous) {
        Instant now = clock.instant();
        String file = props.scopeMappingFile();
        if (file == null || file.isBlank()) {
            return new Snapshot(Map.of(), null, now);
        }
        try {
            byte[] bytes = Files.readAllBytes(Path.of(file));
            String hash = sha256(bytes);
            if (hash.equals(previous.contentHash())) {
                return new Snapshot(previous.byGroup(), hash, now);
            }
            Map<String, List<ScopeGrant>> parsed = parse(bytes);
            // R-SAFE-12: a content change is a security-relevant config event — log with
            // the hash so "who could do what since when" is reconstructible from logs.
            log.info("scope-mapping reloaded from {} (sha256={}, {} groups)", file, hash, parsed.size());
            return new Snapshot(parsed, hash, now);
        } catch (IOException | RuntimeException e) {
            // Fail SAFE, not open: keep the previous known-good mapping, never widen to
            // empty (which would lock everyone out mid-incident) — but log loudly.
            log.error("scope-mapping reload from {} failed — keeping previous mapping: {}", file, e.toString());
            return new Snapshot(previous.byGroup(), previous.contentHash(), now);
        }
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
