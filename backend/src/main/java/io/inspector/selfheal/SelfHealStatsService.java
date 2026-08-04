package io.inspector.selfheal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditEntryRepository;
import io.inspector.audit.AuditOutcome;
import io.inspector.config.InspectorProperties;
import io.inspector.dto.SelfHealStats;
import io.inspector.incident.Incident;
import io.inspector.incident.IncidentOccurrence;
import io.inspector.incident.IncidentOccurrenceRepository;
import io.inspector.incident.IncidentRepository;
import io.inspector.snapshot.AggregationSampledEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * The RETRYING risk lane's backend (RETRYING-RISK-LANE.md, #351): per-class self-heal
 * statistics derived ENTIRELY from the BFF's own Postgres — the incident ledger's occurrence
 * time-series (§3.1 spell extraction) and the audit golden master (§3.3 confound detection) —
 * zero new engine calls, zero new persistence, zero new tables. {@code
 * INSUFFICIENT_HISTORY} is the expected, permanent-for-now answer (measured 2026-08-04,
 * docs/reviews/R2-SELFHEAL-BASELINE-2026-08.md: zero unconfounded completed spells exist in
 * the pilot ledger) — this service is built around that being the common case, not an edge one.
 *
 * <p>Two independent clocks, per design:
 * <ul>
 *   <li><b>Read path</b> ({@link #get}) — derive-on-read, Caffeine-cached per class with a TTL
 *       aligned to the sampler beat (§3.2, panel G5): short enough that a late-arriving retry
 *       audit row cannot stay unaccounted for long, simple enough to need no bespoke
 *       cache-key machinery keyed on "last spell"/"latest audit row" (the design explicitly
 *       sanctions this as the acceptable simple implementation over that alternative).</li>
 *   <li><b>Dwell tick</b> ({@link #onAggregationSampled}) — the §4.2 rule 3 hysteresis/dwell
 *       state machine, which must advance once per COMPLETE sampler cycle (not once per HTTP
 *       read) for "10 consecutive complete cycles" to mean anything. Rides the exact same
 *       {@link AggregationSampledEvent} {@code IncidentLedgerService} already listens to — zero
 *       new scheduling, zero new engine calls, same failure-isolation contract (a listener
 *       failure warns and never breaks the sampler cycle).</li>
 * </ul>
 *
 * <p>Gated by {@code inspector.selfheal.enabled} (default true) — like {@code
 * inspector.incidents.enabled}, this flag gates ONLY the dwell-ticking listener; reads stay
 * live regardless (an unticked class answers the safe {@code INSUFFICIENT_HISTORY} default),
 * mirroring the "reads survive ingestion being off" doctrine {@code IncidentQueryService}
 * already established for the ledger itself.
 *
 * <p><b>Informational only (hard rail, §5).</b> This service has exactly one consumer —
 * {@code IncidentQueryService}, which renders its output as a read-only decoration on {@code
 * IncidentSummary}. Nothing here is referenced by, or references, {@code
 * CorrectiveActionService} or any guard: no verb availability, RBAC tier, or bulk behavior
 * reads it, and no mutation is ever derived from it.
 *
 * <p><b>Attribution gap (§3.3, also tracked by issue #358):</b> per-signature attribution of a
 * FILTER/selection-scoped (or single-target) corrective action is not derivable from any READ
 * API — {@code relatedBulkJobs} only joins ERROR_CLASS-scoped bulk envelopes. This service does
 * NOT attempt to solve that for DISPLAY purposes (R-AUD-03 forbids de-minimizing the audit
 * payload to carry a signature, and no per-item column exists to stamp one into instead). What
 * it DOES solve, audit-side and narrower in scope, is CONFOUND DETECTION: it never needs to
 * know WHICH class a retry targeted, only whether a retry landed on an engine the class
 * touches inside the spell's ±2-bucket window ({@link AuditEntryRepository
 * #findSuccessfulRetryJobAudits}) — engine + timestamp + outcome are all already on every
 * audit row, unminimized, so this reads ONLY existing non-payload columns. The general
 * per-signature attribution gap (episode-level "closed with/without action", and a precise
 * non-ERROR_CLASS join) remains open, exactly as the design records it.
 */
@Service
public class SelfHealStatsService {

    private static final Logger log = LoggerFactory.getLogger(SelfHealStatsService.class);
    private static final TypeReference<Map<String, Map<String, Long>>> COUNTS_SHAPE = new TypeReference<>() {};

    private final IncidentRepository incidents;
    private final IncidentOccurrenceRepository occurrences;
    private final AuditEntryRepository audits;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration window;
    private final Duration bucketWidth;
    private final int floor;
    private final int dwellCycles;
    private final boolean tickEnabled;

    private final Cache<String, RawSelfHealStats> rawCache;
    private final Map<String, DwellState> dwellStates = new ConcurrentHashMap<>();

    public SelfHealStatsService(
            IncidentRepository incidents,
            IncidentOccurrenceRepository occurrences,
            AuditEntryRepository audits,
            ObjectMapper json,
            Clock clock,
            InspectorProperties properties) {
        this.incidents = incidents;
        this.occurrences = occurrences;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
        this.window = Duration.ofDays(properties.selfHealOrDefault().windowDaysOrDefault());
        this.bucketWidth = properties.snapshotOrDefault().bucketWidthOrDefault();
        this.floor = properties.selfHealOrDefault().floorOrDefault();
        this.dwellCycles = properties.selfHealOrDefault().dwellCyclesOrDefault();
        // Deliberately a RUNTIME field check, not @ConditionalOnProperty on this method:
        // that annotation is only evaluated on bean-definition sources (a @Configuration class
        // or a @Bean method) — Spring's EventListenerMethodProcessor does not honor it on a
        // plain @EventListener method of an always-present @Service, which this bean must stay
        // (IncidentQueryService injects it unconditionally so reads survive the flag being off,
        // exactly like inspector.incidents.enabled gating only IncidentLedgerService's own
        // ingestion listener while IncidentQueryService's reads stay live).
        this.tickEnabled = properties.selfHealOrDefault().enabledOrDefault();
        // TTL aligned to the sampler beat (§3.2, panel G5) — the design's sanctioned "acceptable
        // simple implementation" over a bespoke (class, last spell, latest audit row) cache key.
        this.rawCache = Caffeine.newBuilder().expireAfterWrite(bucketWidth).build();
    }

    /** The read path: the DISPLAYED (dwelled) lane, paired with the latest cached raw statistic. */
    public SelfHealStats get(String signatureHash, int algoVersion) {
        String key = key(signatureHash, algoVersion);
        RawSelfHealStats raw = rawCache.get(
                key,
                k -> incidents
                        .findBySignatureHashAndAlgoVersion(signatureHash, algoVersion)
                        .map(row -> computeRaw(row, clock.instant()).raw())
                        .orElse(RawSelfHealStats.insufficientHistory()));
        SelfHealLane displayed =
                dwellStates.getOrDefault(key, DwellState.initial()).displayedLane();
        return toDto(raw, displayed);
    }

    /**
     * The §4.2 rule 3 dwell tick — one pass over the ledger per sampler cycle. Never throws.
     * Gated by {@code inspector.selfheal.enabled} via a runtime check (see the constructor's
     * {@code tickEnabled} note for why this is NOT {@code @ConditionalOnProperty}): reads must
     * stay live even while ticking is off, so this bean always exists.
     */
    @EventListener
    public void onAggregationSampled(AggregationSampledEvent event) {
        if (!tickEnabled) {
            return;
        }
        try {
            tick(event.sample().cycleComplete(), clock.instant());
        } catch (RuntimeException e) {
            log.warn("self-heal stats: dwell tick skipped this cycle — {}", e.toString());
        }
    }

    /** Package-visible so tests drive ticks deterministically (the {@code IncidentLedgerServiceTest} shape). */
    void tick(boolean cycleComplete, Instant now) {
        for (Incident row : incidents.findAll()) {
            String key = key(row.getSignatureHash(), row.getAlgoVersion());
            try {
                ClassCompute computed = computeRaw(row, now);
                rawCache.put(key, computed.raw());
                DwellState previous = dwellStates.getOrDefault(key, DwellState.initial());
                DwellState next = DwellStateMachine.advance(
                        previous,
                        computed.raw().n(),
                        computed.raw().wilsonLow(),
                        computed.raw().wilsonHigh(),
                        floor,
                        computed.liveSpellPresent(),
                        cycleComplete,
                        dwellCycles);
                dwellStates.put(key, next);
            } catch (RuntimeException e) {
                // Informational-only surface (§5): one poisoned class never blocks the ledger's
                // own sampler cycle, and never blocks every OTHER class's dwell tick either.
                log.warn("self-heal stats: class {} skipped this cycle — {}", key, e.toString());
            }
        }
    }

    /* ---------------- derive-on-read ---------------- */

    private record ClassCompute(RawSelfHealStats raw, boolean liveSpellPresent) {}

    private ClassCompute computeRaw(Incident row, Instant now) {
        Instant since = now.minus(window);
        List<IncidentOccurrence> points =
                occurrences.findByIdIncidentIdAndIdSampledAtGreaterThanEqualOrderByIdSampledAtAsc(row.getId(), since);
        List<SpellSample> samples = new ArrayList<>(points.size());
        for (IncidentOccurrence point : points) {
            samples.add(new SpellSample(
                    point.getId().getSampledAt(),
                    point.getDeadLetterCount(),
                    point.getRetryingCount(),
                    point.isTruncated()));
        }
        List<ConfoundWindow> confoundWindows = confoundWindows(row, since);
        List<RetrySpell> spells = RetrySpellExtractor.extract(samples, bucketWidth, confoundWindows);
        RawSelfHealStats raw = SelfHealStatsComputer.compute(spells, floor);
        boolean live = spells.stream().anyMatch(RetrySpell::live);
        return new ClassCompute(raw, live);
    }

    private List<ConfoundWindow> confoundWindows(Incident row, Instant since) {
        Set<String> engineIds = engineIdsOf(row);
        if (engineIds.isEmpty()) {
            return List.of();
        }
        Duration tolerance = bucketWidth.multipliedBy(RetrySpellExtractor.CONFOUND_BUCKETS);
        List<AuditEntry> retries = audits.findSuccessfulRetryJobAudits(engineIds, since, AuditOutcome.ok);
        List<ConfoundWindow> windows = new ArrayList<>(retries.size());
        for (AuditEntry entry : retries) {
            windows.add(new ConfoundWindow(
                    entry.getTs().minus(tolerance), entry.getTs().plus(tolerance)));
        }
        return windows;
    }

    private Set<String> engineIdsOf(Incident row) {
        try {
            Map<String, Map<String, Long>> parsed = json.readValue(row.getCountsByEngine(), COUNTS_SHAPE);
            return parsed != null ? parsed.keySet() : Set.of();
        } catch (JsonProcessingException | RuntimeException e) {
            // Degrade-safe (informational-only surface, §5): a corrupted display blob drops
            // confound detection for this ONE class rather than 500ing the read it decorates.
            log.warn("self-heal stats: counts_by_engine unreadable for incident {} — {}", row.getId(), e.toString());
            return Set.of();
        }
    }

    private SelfHealStats toDto(RawSelfHealStats raw, SelfHealLane displayedLane) {
        boolean aboveFloor = raw.n() >= floor;
        return new SelfHealStats(
                displayedLane.name(),
                raw.n(),
                raw.healed(),
                aboveFloor ? raw.wilsonLow() : null,
                aboveFloor ? raw.wilsonHigh() : null,
                aboveFloor ? raw.ttsP50Seconds() : null,
                aboveFloor ? raw.ttsP90Seconds() : null,
                raw.excludedSpells(),
                raw.truncationTainted());
    }

    private static String key(String signatureHash, int algoVersion) {
        return signatureHash + '#' + algoVersion;
    }
}
