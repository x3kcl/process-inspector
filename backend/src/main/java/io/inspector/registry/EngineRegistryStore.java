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
import io.inspector.security.mapping.FleetGrant;
import io.inspector.security.mapping.MappingSource;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
    private static final Duration PROPOSAL_TTL = Duration.ofHours(24);

    private final EngineRegistryRepository repository;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final RegistryProperties registryProperties;
    private final RbacAuthorizer rbac;
    private final Clock clock;
    private final RegistryWriteProposalRepository proposals;
    private final MappingSource mappingSource;
    private final RegistryPinRegistry pinRegistry;

    // The BFF is single-instance (ARCH §5); registry writes are rare, human-paced admin acts. A JVM
    // lock fully serializes the dangerous-write path (lifecycle guard read → four-eyes decision →
    // mutate/propose) so two concurrent calls can't both observe a pre-mutation state — same TOCTOU
    // guard AccessMappingAdminService uses for the mapping store (S4 review).
    private final Object writeLock = new Object();

    public EngineRegistryStore(
            EngineRegistryRepository repository,
            AuditService audit,
            ApplicationEventPublisher events,
            RegistryProperties registryProperties,
            RbacAuthorizer rbac,
            Clock clock,
            RegistryWriteProposalRepository proposals,
            MappingSource mappingSource,
            RegistryPinRegistry pinRegistry) {
        this.repository = repository;
        this.audit = audit;
        this.events = events;
        this.registryProperties = registryProperties;
        this.rbac = rbac;
        this.clock = clock;
        this.proposals = proposals;
        this.mappingSource = mappingSource;
        this.pinRegistry = pinRegistry;
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
        return recordProbe(id, ok, actor, detail, null);
    }

    /**
     * {@link #recordProbe(String, boolean, Authentication, String)} plus the coarse, UI-safe
     * failure class (issue #275, {@link ProbeFailureClassifier}) — ignored when {@code ok}, and
     * cleared back to null the moment a probe succeeds so a stale class never survives a later
     * green probe.
     */
    @Transactional
    public EngineRegistryRow recordProbe(
            String id, boolean ok, Authentication actor, String detail, String failureClass) {
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
        row.setLastProbeFailureClass(ok ? null : failureClass);
        row.setUpdatedAt(clock.instant());
        repository.save(row);
        // Issue #223: `detail` (the real version string or exception text from the live dial)
        // was previously discarded here in favor of a redundant static "probed"/"probe-failed"
        // literal that just repeats what `outcome` already says — the one place a probe
        // failure's actual cause survived was the controller's now-unused local variable.
        // audit.close already bounds/redacts (SNIPPET_MAX_BYTES, R-AUD-03 payload-mode
        // governance), so this is safe to pass straight through. Still never surfaced to the
        // UI directly (topology-oracle risk, per the controller's own comment) — this is the
        // audit trail the comment always intended, now actually populated.
        String snippet = detail != null && !detail.isBlank() ? detail : (ok ? "probed" : "probe-failed");
        audit.close(entry, ok ? AuditOutcome.ok : AuditOutcome.failed, null, snippet, true);
        events.publishEvent(new RegistryChangedEvent(id));
        return row;
    }

    /**
     * Enable (→ ACTIVE) in the given mode. Requires PROBED or DISABLED (never a raw DRAFT). A prod
     * read-write flip is the dangerous set (R-SAFE-08, #91): it is parked as a four-eyes proposal
     * instead of applied directly. Audited (on the eventual apply, not the propose — the propose
     * itself is audited as {@code registry-proposal} in {@link #propose}).
     */
    @Transactional
    public Outcome enable(String id, boolean readWrite, Authentication actor, String reason) {
        synchronized (writeLock) {
            String actorName = requireRegistryAdmin(actor);
            EngineRegistryRow row = requireRow(id);
            assertEnableAllowed(row);
            if (readWrite
                    && RegistryFourEyesPolicy.requiresFourEyes(
                            RegistryChange.Kind.ENABLE_READ_WRITE, row.getEnvironment())) {
                return propose(RegistryChange.enableReadWrite(id), reason, actor);
            }
            String mode = readWrite ? EngineRegistryMapper.MODE_READ_WRITE : EngineRegistryMapper.MODE_READ_ONLY;
            EngineRegistryRow updated =
                    transition(row, EngineRegistryMapper.LIFECYCLE_ACTIVE, mode, ACTION_ENABLE, actorName, reason);
            return Outcome.applied("enable engine '" + id + "' (" + mode + ")", updated);
        }
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

    /**
     * Soft-delete → tombstone. Requires DISABLED (never a live engine). id→name survives. ALWAYS the
     * dangerous set (R-SAFE-08, #91) regardless of environment — parked as a four-eyes proposal.
     */
    @Transactional
    public Outcome remove(String id, Authentication actor, String reason) {
        synchronized (writeLock) {
            String actorName = requireRegistryAdmin(actor);
            EngineRegistryRow row = requireRow(id);
            assertRemoveAllowed(row);
            if (RegistryFourEyesPolicy.requiresFourEyes(RegistryChange.Kind.REMOVE, row.getEnvironment())) {
                return propose(RegistryChange.remove(id), reason, actor);
            }
            applyRemove(row, actorName, reason);
            return Outcome.applied("remove engine '" + id + "'", row);
        }
    }

    /**
     * Hard-remove a tombstone after retention. Requires a REMOVED row. ALWAYS the dangerous set
     * (R-SAFE-08, #91) regardless of environment — parked as a four-eyes proposal.
     */
    @Transactional
    public Outcome purge(String id, Authentication actor, String reason) {
        synchronized (writeLock) {
            String actorName = requireRegistryAdmin(actor);
            EngineRegistryRow row = repository
                    .findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown engine: " + id));
            assertPurgeAllowed(row);
            if (RegistryFourEyesPolicy.requiresFourEyes(RegistryChange.Kind.PURGE, row.getEnvironment())) {
                return propose(RegistryChange.purge(id), reason, actor);
            }
            applyPurge(row, actorName, reason);
            return Outcome.applied("purge engine '" + id + "'", row);
        }
    }

    /** A second independent REGISTRY_ADMIN approves a pending proposal, which then applies. */
    @Transactional
    public Outcome approve(long proposalId, Authentication auth) {
        synchronized (writeLock) {
            RegistryWriteProposal proposal = proposals
                    .findById(proposalId)
                    .orElseThrow(() -> new IllegalArgumentException("no such proposal: " + proposalId));
            if (proposal.getStatus() != RegistryWriteProposal.Status.PENDING) {
                throw new IllegalStateException("proposal " + proposalId + " is " + proposal.getStatus());
            }
            if (clock.instant().isAfter(proposal.getExpiresAt())) {
                proposal.decide(RegistryWriteProposal.Status.EXPIRED, null, clock.instant());
                proposals.save(proposal);
                throw new IllegalStateException("proposal " + proposalId + " has expired");
            }
            String approverName = requireRegistryAdmin(auth);
            assertEligibleApprover(proposal, approverName, registryAdminGroupsOf(auth));

            EngineRegistryRow row = repository
                    .findById(proposal.getEngineId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "engine no longer exists: " + proposal.getEngineId()));
            String applyReason = "four-eyes approval of proposal #" + proposalId + ": " + proposal.getReason();
            RegistryChange.Kind kind = RegistryChange.Kind.valueOf(proposal.getKind());
            switch (kind) {
                case ENABLE_READ_WRITE -> {
                    // State may have shifted since the proposal was raised — re-verify, not trust.
                    assertEnableAllowed(row);
                    transition(
                            row,
                            EngineRegistryMapper.LIFECYCLE_ACTIVE,
                            EngineRegistryMapper.MODE_READ_WRITE,
                            ACTION_ENABLE,
                            approverName,
                            applyReason);
                }
                case REMOVE -> {
                    assertRemoveAllowed(row);
                    applyRemove(row, approverName, applyReason);
                }
                case PURGE -> {
                    assertPurgeAllowed(row);
                    applyPurge(row, approverName, applyReason);
                }
            }
            proposal.decide(RegistryWriteProposal.Status.APPROVED, approverName, clock.instant());
            proposals.save(proposal);
            return Outcome.applied(proposal.getSummary(), row);
        }
    }

    public List<RegistryWriteProposal> pendingProposals() {
        return proposals.findByStatusOrderByCreatedAtDesc(RegistryWriteProposal.Status.PENDING.name());
    }

    /* -------------------- four-eyes internals -------------------- */

    private Outcome propose(RegistryChange change, String reason, Authentication actor) {
        String proposer = actor.getName();
        Set<String> proposerGroups = registryAdminGroupsOf(actor);
        Set<String> eligible = eligibleApproverGroups(proposerGroups);
        if (eligible.isEmpty()) {
            throw new NoEligibleApproverException(
                    "No eligible independent REGISTRY_ADMIN approver exists for this change — every "
                            + "REGISTRY_ADMIN shares your group(s). Add a second independent REGISTRY_ADMIN "
                            + "group or account before this action can be approved.");
        }
        RegistryWriteProposal proposal = new RegistryWriteProposal(
                proposer,
                String.join(",", proposerGroups),
                change.engineId(),
                change.kind(),
                change.summary(),
                reason,
                clock.instant(),
                clock.instant().plus(PROPOSAL_TTL));
        proposals.save(proposal);
        audit.recordConfigEvent(
                "registry-proposal",
                proposer,
                true,
                Map.of(
                        "summary",
                        change.summary(),
                        "engineId",
                        change.engineId(),
                        "kind",
                        change.kind().name()));
        return Outcome.proposed(proposal.getId(), change.summary(), eligible);
    }

    private void assertEligibleApprover(
            RegistryWriteProposal proposal, String approverName, Set<String> approverGroups) {
        if (approverName.equals(proposal.getProposer())) {
            throw new IneligibleApproverException("the proposer cannot approve their own proposal");
        }
        Set<String> proposerGroups = splitGroups(proposal.getProposerGroups());
        if (!java.util.Collections.disjoint(approverGroups, proposerGroups)) {
            throw new IneligibleApproverException(
                    "an approver sharing the proposer's REGISTRY_ADMIN group is not independent");
        }
    }

    /** This actor's REGISTRY_ADMIN-granting groups (empty for a dev/Basic session — see #91 notes). */
    private Set<String> registryAdminGroupsOf(Authentication actor) {
        Set<String> distinct = distinctRegistryAdminGroups();
        return rbac.oidcGroups(actor).stream().filter(distinct::contains).collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> distinctRegistryAdminGroups() {
        return mappingSource.allFleetGrants().stream()
                .filter(r -> r.grant() == FleetGrant.REGISTRY_ADMIN)
                .map(MappingSource.FleetGrantRow::group)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** REGISTRY_ADMIN groups that are NOT one of the proposer's — the independent approver candidates. */
    private Set<String> eligibleApproverGroups(Set<String> proposerGroups) {
        return distinctRegistryAdminGroups().stream()
                .filter(g -> !proposerGroups.contains(g))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> splitGroups(String joined) {
        if (joined == null || joined.isBlank()) {
            return Set.of();
        }
        return Set.of(joined.split(","));
    }

    private void assertEnableAllowed(EngineRegistryRow row) {
        if (!LIFECYCLE_PROBED.equals(row.getLifecycle())
                && !EngineRegistryMapper.LIFECYCLE_DISABLED.equals(row.getLifecycle())
                && !EngineRegistryMapper.LIFECYCLE_ACTIVE.equals(row.getLifecycle())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "enable requires a probed or disabled engine (current: " + row.getLifecycle() + ")");
        }
    }

    private void assertRemoveAllowed(EngineRegistryRow row) {
        if (!EngineRegistryMapper.LIFECYCLE_DISABLED.equals(row.getLifecycle())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "disable the engine before removing it");
        }
    }

    private void assertPurgeAllowed(EngineRegistryRow row) {
        if (row.getRemovedAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only a removed (tombstoned) engine can be purged");
        }
    }

    private void applyRemove(EngineRegistryRow row, String actorName, String reason) {
        String id = row.getId();
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

    private void applyPurge(EngineRegistryRow row, String actorName, String reason) {
        String id = row.getId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("action", "purge");
        AuditEntry entry =
                audit.beginPending(actorName, id, row.getTenantId(), id, ACTION_PURGE, reason, null, payload);
        repository.delete(row);
        audit.close(entry, AuditOutcome.ok, null, "purged", true);
        events.publishEvent(new RegistryChangedEvent(id));
    }

    /** The outcome of a dangerous-write call: applied now, or parked as a proposal. */
    public record Outcome(
            String status, Long proposalId, Set<String> eligibleApproverGroups, String summary, EngineRegistryRow row) {
        static Outcome applied(String summary, EngineRegistryRow row) {
            return new Outcome("applied", null, Set.of(), summary, row);
        }

        static Outcome proposed(Long id, String summary, Set<String> eligible) {
            return new Outcome("proposed", id, eligible, summary, null);
        }
    }

    /** 409 — no independent approver exists (every REGISTRY_ADMIN group is the proposer's own). */
    public static class NoEligibleApproverException extends RuntimeException {
        public NoEligibleApproverException(String message) {
            super(message);
        }
    }

    /** 403 — this approver is not independent of the proposal. */
    public static class IneligibleApproverException extends RuntimeException {
        public IneligibleApproverException(String message) {
            super(message);
        }
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

    /**
     * SSRF rail (docs §5): reject a base-URL BEFORE any audit/write; the rule name is safe 400 copy.
     * Routes through {@link RegistryPinRegistry} (not the validator directly) so a successful add/edit
     * also (re-)pins the host for connect-time reuse (R-OPS-13, #91).
     */
    private void validateUrl(String baseUrl, EngineEnvironment environment) {
        Result result = pinRegistry.validate(baseUrl, environment, registryProperties.egressPolicy());
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
