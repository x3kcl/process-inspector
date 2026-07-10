package io.inspector.registry;

import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditOutcome;
import io.inspector.audit.AuditService;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.RegistryProperties;
import io.inspector.registry.RegistryDrift.DriftReport;
import io.inspector.registry.RegistryUrlValidator.Rejected;
import io.inspector.registry.RegistryUrlValidator.Result;
import io.inspector.security.RbacAuthorizer;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
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

    private static final Logger log = LoggerFactory.getLogger(EngineRegistryStore.class);

    private static final String SYSTEM_ACTOR = "system";
    private static final String ACTION_SEED = "registry-seed";
    private static final String ACTION_ADD = "registry-add";
    private static final String ACTION_EDIT = "registry-edit";
    private static final String ACTION_PROBE = "registry-probe";
    private static final String ACTION_ENABLE = "registry-enable";
    private static final String ACTION_DISABLE = "registry-disable";
    private static final String ACTION_REMOVE = "registry-remove";
    private static final String ACTION_PURGE = "registry-purge";
    private static final String SEED_REASON =
            "Registry seed: imported inspector.engines YAML into an empty registry (R-OPS-15)";
    private static final Pattern ID_PATTERN = Pattern.compile(InspectorProperties.ENGINE_ID_PATTERN);

    private final EngineRegistryRepository repository;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final RegistryUrlValidator urlValidator;
    private final RegistryProperties registryProperties;
    private final RbacAuthorizer rbac;
    private final Clock clock;

    public EngineRegistryStore(
            EngineRegistryRepository repository,
            AuditService audit,
            ApplicationEventPublisher events,
            RegistryUrlValidator urlValidator,
            RegistryProperties registryProperties,
            RbacAuthorizer rbac,
            Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.events = events;
        this.urlValidator = urlValidator;
        this.registryProperties = registryProperties;
        this.rbac = rbac;
        this.clock = clock;
    }

    /**
     * The SERVICE half of the door-AND-service RBAC doctrine (corrective-actions): every write
     * re-verifies the fleet REGISTRY_ADMIN grant here, not only at the controller's
     * {@code @PreAuthorize} door. Returns the audit actor name.
     */
    private String requireRegistryAdmin(Authentication actor) {
        if (!rbac.canAdministerRegistry(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "requires REGISTRY_ADMIN");
        }
        return actor.getName();
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
    public void editBaseUrl(String id, String newBaseUrl, Authentication actor, String reason) {
        // S6 (security seat): re-check the fleet grant in the SERVICE, exactly like every S4 sibling
        // mutator — the @PreAuthorize door is not the only guard. Without this, whoever wires this
        // seam to an endpoint inherits an unauthenticated registry write (an SSRF repoint).
        String actorName = requireRegistryAdmin(actor);
        EngineRegistryRow row = repository
                .findByIdAndRemovedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown engine: " + id));
        String oldBaseUrl = row.getBaseUrl();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("field", "baseUrl");
        payload.put("before", oldBaseUrl);
        payload.put("after", newBaseUrl);
        AuditEntry entry = audit.beginPending(actorName, id, row.getTenantId(), id, ACTION_EDIT, reason, null, payload);

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

    /* ---------------- S4 admin CRUD + lifecycle (all REGISTRY_ADMIN, audited, reload) ----------------
     *
     * Each write: SSRF-validate the base-URL (add/edit) → audit fail-closed → mutate the row →
     * publish RegistryChangedEvent (AFTER_COMMIT reload). A rejected base-URL throws BEFORE any audit
     * or write (nothing happened; the rule-named 400 is safe copy), logged loudly.
     */

    private static final String LIFECYCLE_DRAFT = "draft";
    private static final String LIFECYCLE_PROBED = "probed";
    private static final String LIFECYCLE_PROBE_FAILED = "probe_failed";
    private static final String LIFECYCLE_REMOVED = "removed";

    /** Every row incl. draft/disabled/tombstoned — the admin list (the enabled-only view is findLive). */
    public List<EngineRegistryRow> findAllForAdmin() {
        return repository.findAll();
    }

    /** A live (non-tombstoned) row, or 404. */
    public EngineRegistryRow requireRow(String id) {
        return repository
                .findByIdAndRemovedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown engine: " + id));
    }

    /** Add a new engine (→ DRAFT, read-only). SSRF-validated, slug- + duplicate-id-checked, audited. */
    @Transactional
    public EngineRegistryRow add(RegistryWrite w, Authentication actor, String reason) {
        String actorName = requireRegistryAdmin(actor);
        String id = w.id();
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "engine id must match " + InspectorProperties.ENGINE_ID_PATTERN);
        }
        if (repository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "engine id already registered: " + id);
        }
        validateUrl(w.baseUrl(), w.environment());

        AuditEntry entry =
                audit.beginPending(actorName, id, w.tenantId(), id, ACTION_ADD, reason, null, writePayload(null, w));
        EngineRegistryRow row = new EngineRegistryRow();
        row.setId(id);
        row.setLifecycle(LIFECYCLE_DRAFT); // born disabled + read-only; trust is earned by a probe
        row.setMode(EngineRegistryMapper.MODE_READ_ONLY);
        row.setSource(EngineRegistryMapper.SOURCE_UI);
        row.setCreatedAt(clock.instant());
        applyWrite(row, w);
        repository.save(row);
        audit.close(entry, AuditOutcome.ok, null, "added", true);
        events.publishEvent(new RegistryChangedEvent(id));
        return row;
    }

    /** Edit everything except {@code id} (immutable). SSRF-re-validated, audited before/after. */
    @Transactional
    public EngineRegistryRow edit(String id, RegistryWrite w, Authentication actor, String reason) {
        String actorName = requireRegistryAdmin(actor);
        EngineRegistryRow row = requireRow(id);
        validateUrl(w.baseUrl(), w.environment());

        Map<String, Object> before = rowPayload(row);
        AuditEntry entry =
                audit.beginPending(actorName, id, w.tenantId(), id, ACTION_EDIT, reason, null, writePayload(before, w));
        applyWrite(row, w);
        repository.save(row);
        audit.close(entry, AuditOutcome.ok, null, "edited", true);
        events.publishEvent(new RegistryChangedEvent(id));
        return row;
    }

    /** Record a read-only probe result — DRAFT/PROBE_FAILED → PROBED | PROBE_FAILED. Audited. */
    @Transactional
    public EngineRegistryRow recordProbe(String id, boolean ok, Authentication actor, String detail) {
        String actorName = requireRegistryAdmin(actor);
        EngineRegistryRow row = requireRow(id);
        String next = ok ? LIFECYCLE_PROBED : LIFECYCLE_PROBE_FAILED;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("from", row.getLifecycle());
        payload.put("to", next);
        AuditEntry entry = audit.beginPending(
                actorName, id, row.getTenantId(), id, ACTION_PROBE, "read-only probe", null, payload);
        row.setLifecycle(next);
        row.setUpdatedAt(clock.instant());
        repository.save(row);
        audit.close(entry, ok ? AuditOutcome.ok : AuditOutcome.failed, null, ok ? "probed" : "probe-failed", true);
        events.publishEvent(new RegistryChangedEvent(id));
        return row;
    }

    /** Enable (→ ACTIVE) in the given mode. Requires PROBED or DISABLED (never a raw DRAFT). Audited. */
    @Transactional
    public EngineRegistryRow enable(String id, boolean readWrite, Authentication actor, String reason) {
        String actorName = requireRegistryAdmin(actor);
        EngineRegistryRow row = requireRow(id);
        if (!LIFECYCLE_PROBED.equals(row.getLifecycle())
                && !EngineRegistryMapper.LIFECYCLE_DISABLED.equals(row.getLifecycle())
                && !EngineRegistryMapper.LIFECYCLE_ACTIVE.equals(row.getLifecycle())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "enable requires a probed or disabled engine (current: " + row.getLifecycle() + ")");
        }
        String mode = readWrite ? EngineRegistryMapper.MODE_READ_WRITE : EngineRegistryMapper.MODE_READ_ONLY;
        return transition(row, EngineRegistryMapper.LIFECYCLE_ACTIVE, mode, ACTION_ENABLE, actorName, reason);
    }

    /** Disable (pause dispatch, R-SEM-11). Requires ACTIVE. Audited. */
    @Transactional
    public EngineRegistryRow disable(String id, Authentication actor, String reason) {
        String actorName = requireRegistryAdmin(actor);
        EngineRegistryRow row = requireRow(id);
        if (!EngineRegistryMapper.LIFECYCLE_ACTIVE.equals(row.getLifecycle())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only an active engine can be disabled");
        }
        return transition(
                row, EngineRegistryMapper.LIFECYCLE_DISABLED, row.getMode(), ACTION_DISABLE, actorName, reason);
    }

    /** Soft-delete → tombstone. Requires DISABLED (never a live engine). id→name survives. Audited. */
    @Transactional
    public void remove(String id, Authentication actor, String reason) {
        String actorName = requireRegistryAdmin(actor);
        EngineRegistryRow row = requireRow(id);
        if (!EngineRegistryMapper.LIFECYCLE_DISABLED.equals(row.getLifecycle())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "disable the engine before removing it");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("action", "tombstone");
        AuditEntry entry =
                audit.beginPending(actorName, id, row.getTenantId(), id, ACTION_REMOVE, reason, null, payload);
        row.setLifecycle(LIFECYCLE_REMOVED);
        row.setRemovedAt(clock.instant());
        row.setUpdatedAt(clock.instant());
        repository.save(row);
        audit.close(entry, AuditOutcome.ok, null, "removed", true);
        events.publishEvent(new RegistryChangedEvent(id));
    }

    /** Hard-remove a tombstone after retention. Requires a REMOVED row. Audited. */
    @Transactional
    public void purge(String id, Authentication actor, String reason) {
        String actorName = requireRegistryAdmin(actor);
        EngineRegistryRow row = repository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown engine: " + id));
        if (row.getRemovedAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only a removed (tombstoned) engine can be purged");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("action", "purge");
        AuditEntry entry =
                audit.beginPending(actorName, id, row.getTenantId(), id, ACTION_PURGE, reason, null, payload);
        repository.delete(row);
        audit.close(entry, AuditOutcome.ok, null, "purged", true);
        events.publishEvent(new RegistryChangedEvent(id));
    }

    private EngineRegistryRow transition(
            EngineRegistryRow row, String lifecycle, String mode, String action, String actor, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", row.getId());
        payload.put("from", row.getLifecycle());
        payload.put("to", lifecycle);
        payload.put("mode", mode);
        AuditEntry entry =
                audit.beginPending(actor, row.getId(), row.getTenantId(), row.getId(), action, reason, null, payload);
        row.setLifecycle(lifecycle);
        row.setMode(mode);
        row.setUpdatedAt(clock.instant());
        repository.save(row);
        audit.close(entry, AuditOutcome.ok, null, lifecycle, true);
        events.publishEvent(new RegistryChangedEvent(row.getId()));
        return row;
    }

    /** SSRF rail (docs §5): reject a base-URL BEFORE any audit/write; the rule name is safe 400 copy. */
    private void validateUrl(String baseUrl, EngineEnvironment environment) {
        Result result = urlValidator.validate(baseUrl, environment, registryProperties.egressPolicy());
        if (result instanceof Rejected rejected) {
            log.warn("Rejected registry base-URL '{}' on rail {}: {}", baseUrl, rejected.rail(), rejected.message());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, rejected.message());
        }
    }

    private void applyWrite(EngineRegistryRow row, RegistryWrite w) {
        row.setName(w.name());
        row.setBaseUrl(w.baseUrl());
        row.setEnvironment(w.environment().name().toLowerCase(Locale.ROOT));
        row.setAccentColor(w.accentColor());
        row.setTenantId(w.tenantId());
        row.setTelemetryUrlTemplate(w.telemetryUrlTemplate());
        row.setAuthType(w.authType() != null ? w.authType() : "none");
        row.setAuthUsername(w.authUsername());
        row.setPasswordRef(w.passwordRef());
        row.setTokenRef(w.tokenRef());
        row.setConnectMs(w.connectMs());
        row.setReadMs(w.readMs());
        row.setWriteMs(w.writeMs());
        row.setMaxPageSize(w.maxPageSize());
        row.setDlqScanCap(w.dlqScanCap());
        row.setAlarmOldestWarnMin(w.alarmOldestWarnMin());
        row.setAlarmOldestCritMin(w.alarmOldestCritMin());
        row.setAlarmOverdueGraceS(w.alarmOverdueGraceS());
        row.setUpdatedAt(clock.instant());
    }

    private static Map<String, Object> rowPayload(EngineRegistryRow row) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", row.getId());
        p.put("name", row.getName());
        p.put("baseUrl", row.getBaseUrl());
        p.put("environment", row.getEnvironment());
        p.put("mode", row.getMode());
        p.put("lifecycle", row.getLifecycle());
        p.put("authType", row.getAuthType());
        p.put("authUsername", row.getAuthUsername());
        p.put("passwordRef", row.getPasswordRef());
        p.put("tokenRef", row.getTokenRef());
        return p;
    }

    private static Map<String, Object> writePayload(Map<String, Object> before, RegistryWrite w) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("id", w.id());
        after.put("name", w.name());
        after.put("baseUrl", w.baseUrl());
        after.put("environment", w.environment() != null ? w.environment().name() : null);
        after.put("authType", w.authType());
        after.put("authUsername", w.authUsername());
        after.put("passwordRef", w.passwordRef());
        after.put("tokenRef", w.tokenRef());
        Map<String, Object> payload = new LinkedHashMap<>();
        if (before != null) {
            payload.put("before", before);
        }
        payload.put("after", after);
        return payload;
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
