package io.inspector.detail;

import io.inspector.client.EngineCallRecorder;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.detail.InstanceDetailService.StatusDerivation;
import io.inspector.dto.InstanceStatusFlags;
import io.inspector.dto.StatusEvidence;
import io.inspector.dto.StatusEvidence.FlagFinding;
import io.inspector.dto.StatusEvidence.Leg;
import io.inspector.registry.EngineRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * "Explain this status" (R-L3-01, SPEC §3): assembles the falsifiable evidence behind a status
 * chip by RE-DERIVING the flags on demand and capturing every engine call the derivation makes.
 *
 * <p>The derivation itself is {@link InstanceDetailService#deriveStatus} — the SAME code that
 * feeds vitals/resolve, so the evidence can never disagree with the chip it explains. The raw
 * per-leg wire detail is captured by {@link EngineCallRecorder} (activated only for the span of
 * this call), and the semantic "flag ⇐ job/child" trail comes from the derivation's provenance.
 * Nothing is retained between requests: this is a fresh derivation, labeled as such.
 */
@Service
public class StatusEvidenceService {

    static final String NOTE = "Re-derived just now against live engine state. The inspector does not retain the"
            + " original response bytes, so this is a fresh derivation — the flags below are re-computed on demand,"
            + " not a replay of what was shown earlier.";

    private final EngineRegistry registry;
    private final InstanceDetailService detail;

    public StatusEvidenceService(EngineRegistry registry, InstanceDetailService detail) {
        this.registry = registry;
        this.detail = detail;
    }

    public StatusEvidence explain(String engineId, String instanceId) {
        EngineConfig engine = registry.require(engineId);
        EngineCallRecorder.begin();
        List<EngineCallRecorder.Leg> recorded;
        StatusDerivation derivation;
        try {
            Map<String, Object> historic = detail.requireHistoric(engine, instanceId);
            derivation = detail.deriveStatus(engine, historic);
        } finally {
            recorded = EngineCallRecorder.end();
        }

        InstanceStatusFlags flags = derivation.flags();
        boolean ended = derivation.ended();
        String plan = ended ? "ENDED_SHORT_CIRCUIT" : "LIVE_LANE_SCAN";
        String planReason = ended
                ? "Instance has ended (endTime set), so it is COMPLETED — the failure lanes are not scanned."
                : "Instance is open, so the runtime state and every failure lane are scanned, plus a bounded"
                        + " call-activity descendant walk for failedInSubprocess.";

        return new StatusEvidence(
                engine.id() + ":" + instanceId,
                engine.id(),
                instanceId,
                flags.primaryStatus(),
                flags,
                plan,
                planReason,
                legs(recorded),
                findings(derivation),
                true,
                Instant.now().toString(),
                NOTE);
    }

    private static List<Leg> legs(List<EngineCallRecorder.Leg> recorded) {
        List<Leg> legs = new ArrayList<>(recorded.size());
        for (EngineCallRecorder.Leg leg : recorded) {
            legs.add(new Leg(
                    labelFor(leg.method(), leg.url()),
                    leg.method(),
                    leg.url(),
                    leg.requestBody(),
                    leg.status(),
                    leg.durationMs(),
                    leg.asOf(),
                    "live"));
        }
        return legs;
    }

    /** A human label for a captured call, derived from its path — the "which leg" of the evidence. */
    static String labelFor(String method, String url) {
        String path = pathOf(url);
        if (path.contains("/history/historic-process-instances/")) return "historic anchor (ended?)";
        if (path.contains("/query/historic-process-instances")) return "descendant walk — child query";
        if (path.endsWith("/runtime/process-instances") || path.matches(".*/runtime/process-instances/[^/]+$")) {
            return "runtime state (suspended?)";
        }
        if (path.contains("/management/deadletter-jobs")) return "dead-letter lane";
        if (path.contains("/management/timer-jobs")) return "timer lane (parked retries)";
        if (path.contains("/management/jobs")) return "executable lane (withException in BFF)";
        return method + " " + path;
    }

    private static String pathOf(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) return url;
        int slash = url.indexOf('/', scheme + 3);
        if (slash < 0) return "/";
        int query = url.indexOf('?', slash);
        return query < 0 ? url.substring(slash) : url.substring(slash, query);
    }

    private static List<FlagFinding> findings(StatusDerivation d) {
        InstanceStatusFlags flags = d.flags();
        List<FlagFinding> findings = new ArrayList<>(5);

        findings.add(new FlagFinding(
                "ended",
                flags.ended(),
                "historic anchor",
                flags.ended()
                        ? "historic row has an endTime — the instance is COMPLETED"
                        : "historic row endTime is null — the instance has not ended",
                null));

        if (d.ended()) {
            // Short-circuit: the failure lanes were never scanned, so the remaining flags are
            // false by construction — say so honestly rather than implying an empty scan.
            findings.add(notScanned("suspended"));
            findings.add(notScanned("hasDeadLetterJobs"));
            findings.add(notScanned("hasFailingJobs"));
            findings.add(notScanned("failedInSubprocess"));
            return findings;
        }

        findings.add(new FlagFinding(
                "suspended",
                flags.suspended(),
                "runtime state",
                flags.suspended() ? "runtime instance carries suspended=true" : "runtime instance is not suspended",
                null));

        findings.add(new FlagFinding(
                "hasDeadLetterJobs",
                flags.hasDeadLetterJobs(),
                "dead-letter lane",
                flags.hasDeadLetterJobs()
                        ? "dead-letter job " + jobList(d.deadLetterJobIds())
                        : "no dead-letter jobs on this instance",
                null));

        findings.add(new FlagFinding(
                "hasFailingJobs",
                flags.hasFailingJobs(),
                "executable + timer lanes (withException)",
                flags.hasFailingJobs()
                        ? "failing (retrying) job " + jobList(d.failingJobIds())
                        : "no failing jobs with retries remaining",
                null));

        findings.add(new FlagFinding(
                "failedInSubprocess",
                flags.failedInSubprocess(),
                "descendant walk",
                flags.failedInSubprocess()
                        ? "call-activity child " + d.failingChildInstanceId() + " holds dead-letter job "
                                + d.failingChildJobId()
                        : "no call-activity descendant holds a dead-letter job",
                flags.failedInSubprocess() ? d.failingChildInstanceId() : null));

        return findings;
    }

    private static FlagFinding notScanned(String flag) {
        return new FlagFinding(flag, false, "not scanned", "instance ended — failure lanes not scanned", null);
    }

    private static String jobList(List<String> ids) {
        if (ids.isEmpty()) return "(id unavailable)";
        String first = ids.get(0);
        return ids.size() == 1 ? first : first + " (+" + (ids.size() - 1) + " more)";
    }
}
