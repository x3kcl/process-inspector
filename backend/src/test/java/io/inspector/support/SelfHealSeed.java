package io.inspector.support;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;

import io.inspector.snapshot.SnapshotBucket;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * REST-only seeding for the issue #359 transiently-failing fixture
 * (docker/processes/demo-self-healing.bpmn20.xml + demo-self-healing-baseline.bpmn20.xml) —
 * the harness capability the R2 self-heal risk lane's design flagged as a blocker (panel G12,
 * docs/RETRYING-RISK-LANE.md §7.2/§10): every OTHER seed fixture fails permanently by
 * construction, so nothing could ever drive a genuine SELF_HEALED retrying spell before this.
 *
 * <p>Used by {@code SelfHealLikelyLaneIT} / {@code SelfHealMixedLaneIT} /
 * {@code SelfHealDwellSuppressionIT}: each deploys its OWN run-unique TRIO ({@link #deploy}) —
 * a HEAL copy, an ESCALATE copy and the standing baseline — which keeps its error signature
 * isolated from every other seed (including a parallel session's) — the same
 * unquoted-letters-token trick {@code IncidentLedgerArcIT} uses, needed here because
 * {@code PropertyNotFoundException}'s message is the literal failing expression text
 * (R-SEM-03 identity), not a process id. All three copies share ONE token, so they collide
 * into a single incident class on purpose; only their retry cascades differ.
 *
 * <p><b>Why the two transient copies exist</b> ({@link #HEAL_RETRY_CYCLE} /
 * {@link #ESCALATE_RETRY_CYCLE}): a spell that must SELF-HEAL needs a cascade long enough to
 * still be retrying when the test heals it, while a spell that must ESCALATE needs one short
 * enough to genuinely exhaust inside an Awaitility bound. One shared cascade cannot serve both
 * — that is precisely how the original {@code R3/PT1S} made {@code SelfHealMixedLaneIT}
 * order-dependent (it holds BOTH spell kinds). Splitting the cascade per role is the fix; both
 * outcomes stay real engine behavior, neither is simulated.
 *
 * <p><b>Deliberately does NOT drive the BPMN's own boundary-timer heal path.</b> That
 * mechanism (a non-interrupting timer racing the retry cascade) is real and is what
 * {@code seed.sh}'s standalone/demo instance relies on, but nominal-duration races against
 * this harness's async-executor acquire lag are NOT reliably deterministic (proven live,
 * 2026-08-04: a 5s boundary timer lost to a 12s-nominal retry cascade). Every instance here
 * is started with {@code healDelay=P1D} (the timer never fires in a test's lifetime) and
 * healed — or deliberately left to escalate — directly over REST at a moment the test
 * chooses, Awaitility-bound against real observed engine state (engine-harness anti-flakiness
 * doctrine: never race a nominal duration, never poll a mutation).
 *
 * <p>A healed instance leaves ZERO inspector audit trail (the REST call goes straight to
 * flowable-rest, never through the BFF's corrective-action door), so RETRYING-RISK-LANE.md
 * §3.3's audit-side confound scan can never flag it — these are genuinely UNCONFOUNDED
 * self-heals, not operator-retry-shaped ones.
 */
public final class SelfHealSeed {

    public static final Path TRANSIENT_BPMN = Path.of("..", "docker", "processes", "demo-self-healing.bpmn20.xml");
    public static final Path BASELINE_BPMN =
            Path.of("..", "docker", "processes", "demo-self-healing-baseline.bpmn20.xml");

    /** The literal cycle in the template, substituted per deployed copy — see {@link #TEMPLATE_RETRY_CYCLE}. */
    private static final String TEMPLATE_RETRY_CYCLE = "R30/PT5S";

    /**
     * The HEAL copy's cascade. A heal spell can only be judged SELF_HEALED if the instance is
     * still RETRYING when {@link #heal} lands, and the test necessarily burns dead time first:
     * {@link #awaitNextBucket} (up to a full bucket) plus one whole {@code sampleOnce()} fleet
     * scan, whose cost grows with the fleet. So this cascade has to outlast that dead time by a
     * wide margin, NOT merely by the seconds a nominal cycle suggests.
     *
     * <p>MEASURED against flowable-6 (2026-08-04, idle engine): the async executor's timer-job
     * acquire poll — not the nominal cycle — dominates, adding ~9-10s to EVERY retry, so a
     * "1 second" cycle really means ~10s per attempt. The old {@code R3/PT1S} therefore
     * dead-lettered <b>20.2s</b> after the first observed failure, i.e. INSIDE the test's own
     * dead time: probe runs healed at +20s found the job already dead-lettered, and a
     * dead-letter job never retries on its own, so {@link #awaitCompleted} could never succeed.
     * That is the whole order-dependence — {@code SelfHealMixedLaneIT} passed alone and failed
     * after {@code SelfHealLikelyLaneIT} had grown the fleet (and with it the scan). At
     * {@code R30/PT5S} the same probe was still retrying at +60s and completed 6.1s after the
     * heal, never dead-lettering: ~29 attempts x ~15s effective is minutes of headroom.
     */
    public static final String HEAL_RETRY_CYCLE = TEMPLATE_RETRY_CYCLE;

    /**
     * The ESCALATE copy's cascade — deliberately the OPPOSITE trade-off, which is exactly why it
     * needs its own deployed copy rather than a shared cycle. An escalation spell must (a) still
     * be RETRYING at its START sample, taken after the same dead time as above, and (b) exhaust
     * into a REAL dead letter inside {@link #awaitDeadLettered}'s bound, with its END sample
     * still within {@code RetrySpellExtractor.GAP_VOID_BUCKETS} of that start. A FEW retries with
     * a WIDE interval satisfies both, where a many-retry cascade would satisfy only (a).
     *
     * <p>MEASURED (same probe, same engine): still retrying at +20s after the first failure, and
     * dead-lettered <b>49.3s</b> after it — a genuine, organic retry exhaustion (nobody moves
     * the job; the last real attempt throws with retries at zero). That sits well inside
     * {@link #awaitDeadLettered}'s bound and puts the START-to-END sample distance at
     * {@code 49s + (bucket wait difference)} — the two scans cancel — i.e. 34-64s at PT15S,
     * inside the 5-bucket (75s) gap-void threshold either way. A WIDER interval would buy
     * retrying margin at the cost of crossing that threshold; this one is centred between them.
     */
    public static final String ESCALATE_RETRY_CYCLE = "R2/PT40S";

    private SelfHealSeed() {}

    /**
     * One test run's deployed trio — a run-unique process-id per role, all three sharing ONE
     * signature token (so they collide into a single incident class on purpose, see class doc).
     */
    public record Fixture(String healKey, String escalateKey, String baselineKey, String token) {}

    /**
     * Deploys a fresh, run-unique copy of the fixture trio sharing one signature token: the
     * HEAL copy ({@link #HEAL_RETRY_CYCLE}), the ESCALATE copy ({@link #ESCALATE_RETRY_CYCLE})
     * and the permanently-failing standing baseline. The two transient copies are the same
     * template with different cascades because the two spell outcomes this fixture has to
     * produce pull in opposite directions — see those two constants.
     */
    public static Fixture deploy(RestClient engine, String label) throws IOException {
        String token = "shz" + label + randomLetters(8);
        String healKey = "itSelfHeal" + label + randomLetters(4);
        String escalateKey = healKey + "Esc";
        String baselineKey = healKey + "Base";
        String template = Files.readString(TRANSIENT_BPMN);
        if (!template.contains(TEMPLATE_RETRY_CYCLE)) {
            // Without this the substitutions below silently no-op and BOTH copies would inherit
            // whatever the template now says — the escalation would stop dead-lettering in time
            // (or the heal path would start racing again) with nothing pointing at the cause.
            fail("demo-self-healing.bpmn20.xml no longer carries the '" + TEMPLATE_RETRY_CYCLE
                    + "' retry cycle this seeder substitutes per copy");
        }
        String baselineXml = Files.readString(BASELINE_BPMN)
                .replace("demoSelfHealingBaseline", baselineKey)
                .replace("selfHealGhost", token);
        deployXml(engine, healKey, transientCopy(template, healKey, token, HEAL_RETRY_CYCLE));
        deployXml(engine, escalateKey, transientCopy(template, escalateKey, token, ESCALATE_RETRY_CYCLE));
        deployXml(engine, baselineKey, baselineXml);
        return new Fixture(healKey, escalateKey, baselineKey, token);
    }

    private static String transientCopy(String template, String key, String token, String retryCycle) {
        return template.replace("demoSelfHealing", key) // process id + every DI id (single literal occurrence)
                .replace("selfHealGhost", token) // the failing expression's identifier
                .replace(TEMPLATE_RETRY_CYCLE, retryCycle);
    }

    private static void deployXml(RestClient engine, String key, String xml) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(xml.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return key + ".bpmn20.xml";
            }
        });
        engine.post()
                .uri("/repository/deployments")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .toBodilessEntity();
        // validate-bpmn §3: deployment success is the definition APPEARING, never the 2xx.
        if (EngineSeed.definitionCount(engine, key) != 1) {
            fail("generated self-heal process '" + key + "' did not deploy/parse");
        }
    }

    /**
     * Starts an instance of the HEAL copy — a long cascade, so it stays RETRYING until the test
     * chooses to {@link #heal} it. {@code healDelay=P1D}: the boundary timer never fires.
     */
    public static String startHealable(RestClient engine, Fixture fixture) {
        return startTransient(engine, fixture.healKey());
    }

    /**
     * Starts an instance of the ESCALATE copy — a short, wide cascade, so leaving it alone
     * exhausts its retries into a REAL dead letter on the test's timescale (nothing simulates
     * the escalation: the engine's own last attempt throws with retries at zero).
     */
    public static String startEscalating(RestClient engine, Fixture fixture) {
        return startTransient(engine, fixture.escalateKey());
    }

    private static String startTransient(RestClient engine, String key) {
        return EngineSeed.startInstance(
                engine, key, null, List.of(Map.of("name", "healDelay", "type", "string", "value", "P1D")));
    }

    /** Starts a standing-baseline instance (R1/PT1S, no boundary timer — dead-letters fast and permanently). */
    public static String startBaseline(RestClient engine, Fixture fixture) {
        return EngineSeed.startInstance(engine, fixture.baselineKey(), null, List.of());
    }

    /**
     * The engine-driven heal, applied directly (never through the BFF): sets {@code healed=true}
     * so the async expression's short-circuit OR skips the throwing branch on the instance's
     * NEXT retry attempt (proven live, 2026-08-04). Leaves no audit row (see class doc).
     */
    public static void heal(RestClient engine, String processInstanceId) {
        engine.post()
                .uri("/runtime/process-instances/" + processInstanceId + "/variables")
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(Map.of("name", "healed", "type", "boolean", "value", true)))
                .retrieve()
                .toBodilessEntity();
    }

    /** Awaits the instance actively RETRYING (the timer withException lane — TriageAggregationService's own tier). */
    public static void awaitRetrying(RestClient engine, String processInstanceId) {
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> EngineSeed.failingTimerCountFor(engine, processInstanceId) >= 1);
    }

    /** Awaits the instance leaving the runtime table via successful completion (never a dead-letter). */
    public static void awaitCompleted(RestClient engine, String processInstanceId) {
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> runtimeGone(engine, processInstanceId));
    }

    /**
     * Awaits the wall clock crossing into a FRESH occurrence bucket relative to the moment this
     * is called. {@code SnapshotSampler#sampleOnce} upserts one occurrence row per (incident,
     * bucket) — {@code Instant.now()} floored to {@code bucketWidth} — so any two
     * {@code sampler.sampleOnce()} calls landing in the SAME bucket silently COLLAPSE into one
     * row (proven live, 2026-08-04: rapid-fire calls collapsed 12+ intended samples into 2 rows,
     * erasing every intermediate RETRYING spell transition). Callers building a spell-boundary
     * chain (RetrySpellExtractor needs each start/end/look-ahead sample as its OWN row) call this
     * immediately before a {@code sampleOnce()} that must be distinguishable from the previous
     * one; when a real engine wait (heal-to-completion, dead-letter exhaustion) already spans
     * more than one bucket, this returns immediately.
     */
    public static void awaitNextBucket(Duration bucketWidth) {
        SnapshotBucket bucket = new SnapshotBucket(bucketWidth);
        Instant startFloor = bucket.floor(Instant.now());
        await().atMost(bucketWidth.plus(Duration.ofSeconds(10)))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> bucket.floor(Instant.now()).isAfter(startFloor));
    }

    /**
     * Awaits the instance's retries exhausting into the dead-letter lane (an ESCALATED spell).
     *
     * <p>Bounded generously on purpose, and this is the ONE bound here where that is sound: the
     * final attempt is a genuinely pending timer, so waiting longer can only ever observe it
     * land. (The mirror-image bound, {@link #awaitCompleted}, is the opposite — once a job has
     * dead-lettered it never retries on its own, so waiting longer there buys nothing at all
     * and only makes a broken run slower. Fix that side with the retry cascade, never the
     * bound.) Measured on flowable-6: ~49s from the first failure at
     * {@link #ESCALATE_RETRY_CYCLE}, and ~10s for the R1 baseline.
     */
    public static void awaitDeadLettered(RestClient engine, String processInstanceId) {
        await().atMost(90, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> EngineSeed.deadLetterCountFor(engine, processInstanceId) >= 1);
    }

    private static boolean runtimeGone(RestClient engine, String processInstanceId) {
        try {
            engine.get()
                    .uri("/runtime/process-instances/" + processInstanceId)
                    .retrieve()
                    .toBodilessEntity();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** KEEP-up hygiene (EngineSeed doctrine): cascade-delete this run's own deployments only. */
    public static void cleanupQuietly(RestClient engine, Fixture fixture) {
        cleanupKeyQuietly(engine, fixture.healKey());
        cleanupKeyQuietly(engine, fixture.escalateKey());
        cleanupKeyQuietly(engine, fixture.baselineKey());
    }

    @SuppressWarnings("unchecked")
    private static void cleanupKeyQuietly(RestClient engine, String key) {
        try {
            Map<String, Object> page = engine.get()
                    .uri("/repository/process-definitions?key=" + key + "&size=100")
                    .retrieve()
                    .body(Map.class);
            List<Map<String, Object>> data = (List<Map<String, Object>>) page.get("data");
            if (data == null) {
                return;
            }
            data.stream()
                    .map(d -> String.valueOf(d.get("deploymentId")))
                    .distinct()
                    .forEach(dep -> {
                        try {
                            engine.delete()
                                    .uri("/repository/deployments/" + dep + "?cascade=true")
                                    .retrieve()
                                    .toBodilessEntity();
                        } catch (RuntimeException e) {
                            // best-effort residue cleanup
                        }
                    });
        } catch (RuntimeException e) {
            // best-effort residue cleanup
        }
    }

    /** Letters the R-SEM-03 sanitizer can never collapse (no digits, no a-f hex ambiguity). */
    private static String randomLetters(int n) {
        String alphabet = "ghjkmnpqrstuvwxyz";
        Random random = new Random();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
