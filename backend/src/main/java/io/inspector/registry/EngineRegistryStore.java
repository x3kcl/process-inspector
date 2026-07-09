package io.inspector.registry;

import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditOutcome;
import io.inspector.audit.AuditService;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.RegistryDrift.DriftReport;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional store over V7 {@code engine_registry} (docs/REGISTRY-CRUD.md §4/§10). In S2 it
 * owns the READ path ({@link #findLive()} → the {@link EngineConfig}s S3 will feed
 * {@code EngineRegistry}) and the one-time YAML SEED import; the CRUD write verbs (add/edit/
 * enable/…) land in S4 on top of this same fail-closed audit rail.
 *
 * <p>The seed is audited fail-closed exactly like an engine mutation (this is higher-privilege than
 * any tier-3 verb): the audit row is inserted BEFORE the registry row and both commit in ONE
 * transaction — an audit failure rolls the whole import back, so a down audit store yields ZERO
 * registrations, never a silent partial seed.
 */
@Service
public class EngineRegistryStore {

    private static final String SYSTEM_ACTOR = "system";
    private static final String ACTION_SEED = "registry-seed";
    private static final String SEED_REASON =
            "Registry seed: imported inspector.engines YAML into an empty registry (R-OPS-15)";

    private final EngineRegistryRepository repository;
    private final AuditService audit;
    private final Clock clock;

    public EngineRegistryStore(EngineRegistryRepository repository, AuditService audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    /** True when no engine has ever been registered — the seed trigger. */
    public boolean isEmpty() {
        return repository.count() == 0;
    }

    /** Every live (non-tombstoned) engine as an {@link EngineConfig} — the S3 registry source. */
    public List<EngineConfig> findLive() {
        return repository.findByRemovedAtIsNull().stream()
                .map(EngineRegistryMapper::toEngineConfig)
                .toList();
    }

    /**
     * Import the YAML engine list into an empty registry as {@code yaml-seed} rows, each audited
     * {@code registry-seed}, all in ONE transaction (fail-closed). Returns the number seeded.
     */
    @Transactional
    public int seedFromConfig(List<EngineConfig> engines) {
        int seeded = 0;
        for (EngineConfig engine : engines) {
            AuditEntry entry = audit.beginPending(
                    SYSTEM_ACTOR,
                    engine.id(),
                    engine.tenantId(),
                    engine.id(),
                    ACTION_SEED,
                    SEED_REASON,
                    null,
                    seedPayload(engine));
            repository.save(EngineRegistryMapper.toSeedRow(engine, clock.instant()));
            audit.close(entry, AuditOutcome.ok, null, "seeded", true);
            seeded++;
        }
        return seeded;
    }

    /** The DB-vs-YAML drift for the boot report / admin badge — never mutates. */
    public DriftReport driftReport(List<EngineConfig> yamlEngines) {
        return RegistryDrift.compute(yamlEngines, findLive());
    }

    /**
     * The audited before/after config, secret refs included by NAME — {@link AuditService#redact}
     * runs them through the denylist redactor (design §2/§7: safe names, redacted anyway). Never a
     * secret value (they are env refs and never enter this object).
     */
    private static Map<String, Object> seedPayload(EngineConfig engine) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", engine.id());
        p.put("name", engine.name());
        p.put("baseUrl", engine.baseUrl());
        p.put("environment", engine.environment().name());
        p.put("mode", engine.modeOrDefault().name());
        p.put("enabled", engine.enabled());
        if (engine.auth() != null) {
            p.put("authType", engine.auth().type().name());
            p.put("authUsername", engine.auth().username());
            p.put("passwordRef", engine.auth().passwordRef());
            p.put("tokenRef", engine.auth().tokenRef());
        }
        p.put("source", EngineRegistryMapper.SOURCE_YAML_SEED);
        return p;
    }
}
