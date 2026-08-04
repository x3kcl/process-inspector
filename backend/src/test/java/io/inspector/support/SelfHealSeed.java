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
 * REST-only seeding for the issue #359 transiently-failing fixture pair
 * (docker/processes/demo-self-healing.bpmn20.xml + demo-self-healing-baseline.bpmn20.xml) —
 * the harness capability the R2 self-heal risk lane's design flagged as a blocker (panel G12,
 * docs/RETRYING-RISK-LANE.md §7.2/§10): every OTHER seed fixture fails permanently by
 * construction, so nothing could ever drive a genuine SELF_HEALED retrying spell before this.
 *
 * <p>Used by {@code SelfHealLikelyLaneIT} / {@code SelfHealMixedLaneIT} /
 * {@code SelfHealDwellSuppressionIT}: each deploys its OWN run-unique copy of the pair
 * ({@link #deploy}), which keeps its error signature isolated from every other seed (including
 * a parallel session's) — the same unquoted-letters-token trick {@code IncidentLedgerArcIT}
 * uses, needed here because {@code PropertyNotFoundException}'s message is the literal failing
 * expression text (R-SEM-03 identity), not a process id.
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

    private SelfHealSeed() {}

    /** One test run's deployed pair — a run-unique process-id AND signature-token per {@code label}. */
    public record Fixture(String transientKey, String baselineKey, String token) {}

    /**
     * Deploys a fresh, run-unique copy of both fixtures sharing one signature token.
     * {@code retryCycle} overrides the transient template's demo default (R30/PT5S, tuned for
     * the boundary-timer race) — tests use a short cycle (e.g. {@code R3/PT1S}) since they
     * drive healing/escalation directly instead of racing the timer.
     */
    public static Fixture deploy(RestClient engine, String label, String retryCycle) throws IOException {
        String token = "shz" + label + randomLetters(8);
        String transientKey = "itSelfHeal" + label + randomLetters(4);
        String baselineKey = transientKey + "Base";
        String transientXml = Files.readString(TRANSIENT_BPMN)
                .replace("demoSelfHealing", transientKey) // process id + every DI id (single literal occurrence)
                .replace("selfHealGhost", token) // the failing expression's identifier
                .replace("R30/PT5S", retryCycle);
        String baselineXml = Files.readString(BASELINE_BPMN)
                .replace("demoSelfHealingBaseline", baselineKey)
                .replace("selfHealGhost", token);
        deployXml(engine, transientKey, transientXml);
        deployXml(engine, baselineKey, baselineXml);
        return new Fixture(transientKey, baselineKey, token);
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

    /** Starts a transient (may-heal-or-escalate) instance. {@code healDelay=P1D}: the boundary timer never fires. */
    public static String startTransient(RestClient engine, Fixture fixture) {
        return EngineSeed.startInstance(
                engine,
                fixture.transientKey(),
                null,
                List.of(Map.of("name", "healDelay", "type", "string", "value", "P1D")));
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

    /** Awaits the instance's retries exhausting into the dead-letter lane (an ESCALATED spell). */
    public static void awaitDeadLettered(RestClient engine, String processInstanceId) {
        await().atMost(60, TimeUnit.SECONDS)
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
        cleanupKeyQuietly(engine, fixture.transientKey());
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
