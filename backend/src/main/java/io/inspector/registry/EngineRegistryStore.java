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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
    private static final String ACTION_EDIT = "registry-edit";
    private static final String SEED_REASON =
            "Registry seed: imported inspector.engines YAML into an empty registry (R-OPS-15)";

    private final EngineRegistryRepository repository;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public EngineRegistryStore(
            EngineRegistryRepository repository, AuditService audit, ApplicationEventPublisher events, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.events = events;
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

    /**
     * Edit a live engine's base-URL (the S3 seam that exercises hot-reload; S4 generalizes this to
     * full add/edit/enable/disable). Audited {@code registry-edit} fail-closed before the write, and
     * a {@link RegistryChangedEvent} is published so the {@code AFTER_COMMIT} reload listener
     * refreshes the registry + evicts the cached client once (and only if) this transaction commits.
     * {@code id} is immutable — only the base-URL changes here.
     */
    @Transactional
    public void editBaseUrl(String id, String newBaseUrl, String actor, String reason) {
        EngineRegistryRow row = repository
                .findByIdAndRemovedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown engine: " + id));
        String oldBaseUrl = row.getBaseUrl();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("field", "baseUrl");
        payload.put("before", oldBaseUrl);
        payload.put("after", newBaseUrl);
        AuditEntry entry = audit.beginPending(actor, id, row.getTenantId(), id, ACTION_EDIT, reason, null, payload);

        row.setBaseUrl(newBaseUrl);
        row.setUpdatedAt(clock.instant());
        repository.save(row);
        audit.close(entry, AuditOutcome.ok, null, "edited", true);

        // Published inside the tx; AFTER_COMMIT delivery means a rollback (row or audit) → no reload.
        events.publishEvent(new RegistryChangedEvent(id));
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
