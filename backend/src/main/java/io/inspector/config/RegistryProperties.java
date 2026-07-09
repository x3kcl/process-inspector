package io.inspector.config;

import io.inspector.registry.RegistryEgressPolicy;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code inspector.registry} block (docs/REGISTRY-CRUD.md §3) — the runtime-registry
 * knobs, distinct from the per-engine {@code inspector.engines} list in {@link InspectorProperties}.
 *
 * <ul>
 *   <li>{@code source} — {@code db} (default): DB-authoritative once initialized, YAML is the
 *       one-time seed. {@code config}: pin to config-only (CRUD disabled, R-SEM-17 restart
 *       semantics restored) — the air-gap / locked-down posture.</li>
 *   <li>{@code egress-allowlist} / {@code egress-ports} — the SSRF egress boundary
 *       ({@link RegistryEgressPolicy}). DEPLOY CONFIG, never editable in-app: an admin who could
 *       edit the allowlist that bounds them has no allowlist. Consumed by the SSRF validator when
 *       it is wired into the write/connect path (S3/S4). Empty ports ⇒ any TCP port on an
 *       allowlisted host.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "inspector.registry")
public record RegistryProperties(Source source, List<String> egressAllowlist, Set<Integer> egressPorts) {

    public enum Source {
        DB,
        CONFIG
    }

    /** Default nulls: DB-authoritative, empty allowlist (which permits nothing until configured). */
    public RegistryProperties {
        source = source != null ? source : Source.DB;
        egressAllowlist = egressAllowlist != null ? List.copyOf(egressAllowlist) : List.of();
        egressPorts = egressPorts != null ? Set.copyOf(egressPorts) : Set.of();
    }

    public boolean isConfigPinned() {
        return source == Source.CONFIG;
    }

    /** The SSRF egress boundary as a validator-ready policy. */
    public RegistryEgressPolicy egressPolicy() {
        return RegistryEgressPolicy.of(egressAllowlist, egressPorts);
    }
}
