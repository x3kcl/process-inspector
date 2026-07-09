package io.inspector.security.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditService;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.ScopeMappingService;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * The governed mapping-CRUD service (IDP-SECURITY.md §6/§9, R-SAFE-14) — {@code @Profile("db")}, so
 * under {@code mapping-source: file} the CRUD controller 409s (no bean). Enforces, in order: CRUD
 * enabled only with ≥2 independent {@code ACCESS_ADMIN} groups; the ≥2-invariant preserved on an
 * apex removal; the four-eyes gate on the RESOLVED change (self-widen / wildcard breadth / any fleet
 * create / any fleet removal) → a PENDING proposal a second independent {@code ACCESS_ADMIN} must
 * approve; the eligible-approver set computed NOW (empty ⇒ refuse with the file-pin next-move);
 * fail-closed audit ({@code grant-*}); and a security-alert fire on every {@code ACCESS_ADMIN} change.
 */
@Service
@Profile("db")
public class AccessMappingAdminService {

    private static final Duration PROPOSAL_TTL = Duration.ofHours(24);

    // The BFF is single-instance (ARCH §5); mapping writes are rare, human-paced admin acts. A
    // JVM lock fully serializes them so the read-check-then-apply (CRUD-enabled, the ≥2-apex
    // invariant, the duplicate/four-eyes-count reads) is atomic w.r.t. the mutation — no TOCTOU
    // where two concurrent apex removals both see ≥2 and drop below it (Copilot S4 review).
    private final Object writeLock = new Object();

    private final MappingStore store;
    private final MappingSource mappingSource;
    private final DbMappingSource dbSource;
    private final AccessGrantProposalRepository proposals;
    private final AuditService audit;
    private final SecurityAlertChannel alert;
    private final RbacAuthorizer rbac;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ScopeMappingService file;
    private final MappingProperties mappingProps;
    private final GroupScopeGrantRepository ladderRepo;

    public AccessMappingAdminService(
            MappingStore store,
            MappingSource mappingSource,
            DbMappingSource dbSource,
            AccessGrantProposalRepository proposals,
            AuditService audit,
            SecurityAlertChannel alert,
            RbacAuthorizer rbac,
            ObjectMapper mapper,
            Clock clock,
            ScopeMappingService file,
            MappingProperties mappingProps,
            GroupScopeGrantRepository ladderRepo) {
        this.store = store;
        this.mappingSource = mappingSource;
        this.dbSource = dbSource;
        this.proposals = proposals;
        this.audit = audit;
        this.alert = alert;
        this.rbac = rbac;
        this.mapper = mapper;
        this.clock = clock;
        this.file = file;
        this.mappingProps = mappingProps;
        this.ladderRepo = ladderRepo;
    }

    /**
     * DB-vs-mounted-file drift (§6/§10). Under db mode the DB wins; the mounted file is only a seed,
     * so a divergence is reported (never silently re-imported). Hard-alerts if pinning back to the
     * file would leave NO resolvable ACCESS_ADMIN (a pin target that can't self-administer = lock-out).
     */
    public DriftReport drift() {
        Set<String> fileGroups = file.allGrantsByGroup().keySet();
        Set<String> dbGroups = ladderRepo.findAll().stream()
                .map(GroupScopeGrantEntity::getGroupName)
                .collect(Collectors.toUnmodifiableSet());
        List<String> onlyInFile =
                fileGroups.stream().filter(g -> !dbGroups.contains(g)).sorted().toList();
        List<String> onlyInDb =
                dbGroups.stream().filter(g -> !fileGroups.contains(g)).sorted().toList();
        // File-mode ACCESS_ADMIN comes only from the env-bootstrap apex; without it a file-pin is a
        // no-apex lock-out (⚠️ DevOps). Surface it AND fire the alert, never a silent all-clear.
        boolean fileWouldHaveApex = mappingProps.accessAdminGroupOrNull() != null;
        if (!fileWouldHaveApex) {
            alert.fire(
                    "mapping-drift-no-file-apex",
                    "pinning to the mounted file would leave NO resolvable ACCESS_ADMIN "
                            + "(INSPECTOR_ACCESS_ADMIN_GROUP is unset)");
        }
        return new DriftReport(onlyInFile, onlyInDb, fileWouldHaveApex);
    }

    public record DriftReport(List<String> onlyInFile, List<String> onlyInDb, boolean fileWouldHaveApex) {}

    /** Apply a single-actor change, or park a widening one as a four-eyes proposal. */
    public Outcome submit(GrantChange change, String reason, Authentication auth) {
        synchronized (writeLock) {
            assertCrudEnabled();
            assertInvariantPreserved(change);

            Set<String> editorGroups = Set.copyOf(rbac.oidcGroups(auth));
            if (FourEyesPolicy.requiresFourEyes(change, editorGroups)) {
                return propose(change, reason, actor(auth));
            }
            applyAudited(change, actor(auth), reason);
            return Outcome.applied(change.summary());
        }
    }

    /** A second independent ACCESS_ADMIN approves a pending proposal, which then applies. */
    public Outcome approve(long proposalId, Authentication auth) {
        synchronized (writeLock) {
            AccessGrantProposal proposal = proposals
                    .findById(proposalId)
                    .orElseThrow(() -> new IllegalArgumentException("no such proposal: " + proposalId));
            if (proposal.getStatus() != AccessGrantProposal.Status.PENDING) {
                throw new IllegalStateException("proposal " + proposalId + " is " + proposal.getStatus());
            }
            if (clock.instant().isAfter(proposal.getExpiresAt())) {
                proposal.decide(AccessGrantProposal.Status.EXPIRED, null, clock.instant());
                proposals.save(proposal);
                throw new IllegalStateException("proposal " + proposalId + " has expired");
            }
            String approver = actor(auth);
            assertEligibleApprover(proposal, approver, Set.copyOf(rbac.oidcGroups(auth)));

            GrantChange change = deserialize(proposal.getChangeJson());
            // Integrity: the change we're about to apply must be the one the approver is approving.
            // A stored change_json whose re-derived summary ≠ the stored (approver-visible) summary is
            // a tampered/corrupt proposal — refuse, never silently apply a different grant (Copilot S4).
            if (!change.summary().equals(proposal.getSummary())) {
                throw new IllegalStateException("proposal " + proposalId + " failed its integrity check");
            }
            assertInvariantPreserved(change); // state may have shifted since the proposal was raised
            applyAudited(
                    change, approver, "four-eyes approval of proposal #" + proposalId + ": " + proposal.getReason());
            proposal.decide(AccessGrantProposal.Status.APPROVED, approver, clock.instant());
            proposals.save(proposal);
            return Outcome.applied(change.summary());
        }
    }

    public List<AccessGrantProposal> pendingProposals() {
        return proposals.findByStatusOrderByCreatedAtDesc(AccessGrantProposal.Status.PENDING.name());
    }

    /* -------------------- internals -------------------- */

    private Outcome propose(GrantChange change, String reason, String proposer) {
        Set<String> eligible = eligibleApproverGroups(change.group());
        if (eligible.isEmpty()) {
            throw new NoEligibleApproverException(
                    "No eligible approver for this change — every ACCESS_ADMIN group is the affected group '"
                            + change.group() + "'. Recover via the file-pin (mapping-source: file), see RUNBOOK.");
        }
        AccessGrantProposal proposal = new AccessGrantProposal(
                proposer,
                change.group(),
                change.kind(),
                serialize(change),
                change.summary(),
                reason,
                clock.instant(),
                clock.instant().plus(PROPOSAL_TTL));
        proposals.save(proposal);
        // ACCESS_ADMIN changes ALWAYS also fire the detective alert (§9), in addition to four-eyes.
        if (FourEyesPolicy.firesSecurityAlert(change)) {
            alert.fire("access-admin-grant-proposed", change.summary() + " (proposed by " + proposer + ")");
        }
        audit.recordConfigEvent(
                "grant-proposal", proposer, true, Map.of("summary", change.summary(), "group", change.group()));
        return Outcome.proposed(proposal.getId(), change.summary(), eligible);
    }

    /** Fail-closed audit around the store mutation (audit-first: no audit ⇒ no change). */
    private void applyAudited(GrantChange change, String actor, String reason) {
        String action = change.isAdd() ? "grant-add" : "grant-remove";
        AuditEntry entry = audit.beginPending(
                actor, AuditService.CONFIG_ENGINE_ID, null, change.group(), action, reason, null, payload(change));
        boolean ok = false;
        try {
            store.apply(change);
            ok = true;
        } finally {
            audit.close(
                    entry,
                    ok ? io.inspector.audit.AuditOutcome.ok : io.inspector.audit.AuditOutcome.failed,
                    null,
                    change.summary(),
                    ok);
        }
        dbSource.refresh(); // afterCommit-style: a committed grant is picked up at once
        if (FourEyesPolicy.firesSecurityAlert(change)) {
            alert.fire("access-admin-grant-change", change.summary() + " (by " + actor + ")");
        }
    }

    private void assertCrudEnabled() {
        if (distinctAccessAdminGroups().size() < 2) {
            throw new CrudDisabledException(
                    "mapping CRUD is disabled: it needs ≥2 independent ACCESS_ADMIN groups so a widening change has "
                            + "an independent four-eyes approver (see RUNBOOK).");
        }
    }

    private void assertInvariantPreserved(GrantChange change) {
        if (change.kind() == GrantChange.Kind.FLEET_REMOVE && change.fleetGrant() == FleetGrant.ACCESS_ADMIN) {
            // Removing an apex must never drop the effective set below 2 (a coerced apex could
            // otherwise remove every other and become the sole authority — §3.4 takeover guard).
            if (distinctAccessAdminGroups().size() <= 2) {
                throw new ApexInvariantException(
                        "refused: removing this ACCESS_ADMIN grant would leave fewer than 2 independent apex groups.");
            }
        }
    }

    private void assertEligibleApprover(AccessGrantProposal proposal, String approver, Set<String> approverGroups) {
        if (approver.equals(proposal.getProposer())) {
            throw new IneligibleApproverException("the proposer cannot approve their own proposal");
        }
        if (approverGroups.contains(proposal.getGroupName())) {
            throw new IneligibleApproverException(
                    "an approver in the affected group ('" + proposal.getGroupName() + "') is not independent");
        }
    }

    private Set<String> distinctAccessAdminGroups() {
        return mappingSource.allFleetGrants().stream()
                .filter(r -> r.grant() == FleetGrant.ACCESS_ADMIN)
                .map(MappingSource.FleetGrantRow::group)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** ACCESS_ADMIN groups that are NOT the affected group — the independent approver candidates. */
    private Set<String> eligibleApproverGroups(String affectedGroup) {
        return distinctAccessAdminGroups().stream()
                .filter(g -> !g.equals(affectedGroup))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String actor(Authentication auth) {
        return auth != null ? auth.getName() : "unknown";
    }

    private Map<String, Object> payload(GrantChange change) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("change", change.summary());
        p.put("group", change.group());
        p.put("kind", change.kind().name());
        return p;
    }

    private String serialize(GrantChange change) {
        try {
            return mapper.writeValueAsString(change);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize grant change", e);
        }
    }

    private GrantChange deserialize(String json) {
        try {
            return mapper.readValue(json, GrantChange.class);
        } catch (Exception e) {
            throw new IllegalStateException("cannot deserialize grant change", e);
        }
    }

    /* -------------------- result + typed failures -------------------- */

    public record Outcome(String status, Long proposalId, String summary, Set<String> eligibleApproverGroups) {
        static Outcome applied(String summary) {
            return new Outcome("applied", null, summary, Set.of());
        }

        static Outcome proposed(Long id, String summary, Set<String> eligible) {
            return new Outcome("proposed", id, summary, eligible);
        }
    }

    /** 409 — CRUD off (fewer than 2 apex groups). */
    public static class CrudDisabledException extends RuntimeException {
        public CrudDisabledException(String message) {
            super(message);
        }
    }

    /** 409 — the ≥2-ACCESS_ADMIN invariant would be violated. */
    public static class ApexInvariantException extends RuntimeException {
        public ApexInvariantException(String message) {
            super(message);
        }
    }

    /** 409 — no independent approver exists (every apex group is the affected group). */
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
}
