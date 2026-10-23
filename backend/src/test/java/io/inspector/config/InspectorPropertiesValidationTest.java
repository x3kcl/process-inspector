package io.inspector.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.inspector.config.InspectorProperties.AlarmThresholds;
import io.inspector.config.InspectorProperties.Attention;
import io.inspector.config.InspectorProperties.Auth;
import io.inspector.config.InspectorProperties.Bulk;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.Incidents;
import io.inspector.config.InspectorProperties.SelfHeal;
import io.inspector.config.InspectorProperties.Snapshot;
import io.inspector.config.InspectorProperties.Timeouts;
import io.inspector.config.InspectorProperties.Triage;
import io.inspector.support.TestEngines;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Rung 1: registry validation is pure record logic — no Spring context. */
class InspectorPropertiesValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"orders-prod", "a", "x.y_z-1", "engine-7", "0abc"})
    void acceptsValidEngineIds(String id) {
        assertThat(violationsOn(TestEngines.engine(id, "http://localhost:8081/x"), "id"))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Orders-Prod", "-x", ".x", "a:b", "a b", "ä", ""})
    void rejectsInvalidEngineIds(String id) {
        assertThat(violationsOn(TestEngines.engine(id, "http://localhost:8081/x"), "id"))
                .isNotEmpty();
    }

    @Test
    void rejectsSixtyFiveCharacterId() {
        String tooLong = "a".repeat(65);
        assertThat(violationsOn(TestEngines.engine(tooLong, "http://localhost:8081/x"), "id"))
                .isNotEmpty();
        assertThat(violationsOn(TestEngines.engine("a".repeat(64), "http://localhost:8081/x"), "id"))
                .isEmpty();
    }

    @Test
    void rejectsMissingBaseUrl() {
        assertThat(violationsOn(TestEngines.engine("ok-id", " "), "baseUrl")).isNotEmpty();
    }

    @Test
    void duplicateEngineIdsFailFastNamingTheOffender() {
        List<EngineConfig> twice = List.of(
                TestEngines.engine("orders-prod", "http://a/x"), TestEngines.engine("orders-prod", "http://b/x"));
        assertThatThrownBy(() -> new InspectorProperties(4, null, null, null, twice))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orders-prod");
    }

    @Test
    void nullEnginesListBindsToEmpty() {
        assertThat(new InspectorProperties(4, null, null, null, null).engines()).isEmpty();
    }

    @Test
    void timeoutAndThresholdDefaults() {
        EngineConfig engine = TestEngines.engine("defaults", "http://a/x");
        assertThat(engine.timeoutsOrDefault().connect()).isEqualTo(2000);
        assertThat(engine.timeoutsOrDefault().read()).isEqualTo(10000);
        // write budget defaults to the read budget (R-NFR-07)
        assertThat(engine.timeoutsOrDefault().write()).isEqualTo(10000);
        assertThat(new InspectorProperties.Timeouts(null, 7000, null).write()).isEqualTo(7000);
        assertThat(engine.alarmsOrDefault().oldestJobWarnMinOrDefault()).isEqualTo(5);
        assertThat(engine.alarmsOrDefault().oldestJobCritMinOrDefault()).isEqualTo(15);
        assertThat(engine.alarmsOrDefault().overdueTimerGraceSOrDefault()).isEqualTo(60);
        assertThat(engine.modeOrDefault()).isEqualTo(InspectorProperties.EngineMode.READ_WRITE);
        assertThat(engine.dlqScanCapOrDefault()).isEqualTo(5000);
    }

    /* ---------------- #365: the burst knobs and their two rails (ALARM-COST-MODEL §14.5) ------ */

    @Test
    void theBurstDefaultsAreISA182sOwnFloodWindowAndAsymmetricThresholds() {
        InspectorProperties.Attention defaults =
                new InspectorProperties.Triage(null, null, null, null).attentionOrDefault();

        assertThat(defaults.burstWindowOrDefault()).isEqualTo(java.time.Duration.ofMinutes(10));
        assertThat(defaults.burstOnsetOrDefault()).isEqualTo(10);
        assertThat(defaults.burstExitOrDefault()).isEqualTo(5);
        assertThat(defaults.burstWeightOrDefault()).isEqualTo(8.0);
        assertThat(defaults.isBurstHysteresisOrdered()).isTrue();
    }

    @Test
    void anExitAboveTheOnsetIsREFUSEDAtBindingRatherThanQuietlyReinterpreted() {
        // With exit > onset the hold leg would admit bursts the entry leg rejects — a gate that
        // fires on evidence too weak to have opened it. There is no sane reinterpretation, so the
        // binding fails instead of guessing at intent. "No hysteresis" is spelled exit = onset.
        assertThat(validator.validate(attention(10, 20))).isNotEmpty();
        assertThat(validator.validate(attention(10, 10))).isEmpty();
        assertThat(validator.validate(attention(10, 5))).isEmpty();
        assertThat(validator.validate(attention(10, 20)).iterator().next().getMessage())
                .contains("burst-exit must be <= burst-onset");
    }

    private static InspectorProperties.Attention attention(int onset, int exit) {
        return new InspectorProperties.Attention(
                null, null, null, null, null, null, null, null, null, null, null, onset, exit, null);
    }

    /* ---------------- #403: the self-heal floor is a probability, and refused if it is not ---- */

    @Test
    void aSelfHealFloorAboveOneIsREFUSEDBecauseItWouldInvertSFromDemotionIntoPromotion() {
        // S = max(floor, 1 - p_heal*w) is a DEMOTION. At floor = 2.0 every class with a self-heal
        // lane scores S = 2.0 — max(2.0, 0.25) with a timely heal, max(2.0, 1.0) with none — so a
        // knob whose entire purpose is to push proven self-healers DOWN instead doubles their
        // score and promotes them to the top of the board. Refused rather than clamped: silently
        // correcting to 1.0 would hide the misconfiguration from the operator who wrote it.
        assertThat(validator.validate(attentionWithFloor(2.0))).isNotEmpty();
        assertThat(validator.validate(attentionWithFloor(2.0)).iterator().next().getMessage())
                .contains("self-heal-floor must be within [0, 1]");

        // The boundary itself binds cleanly, and both endpoints are meaningful: 1.0 is "never
        // demote at all", 0.0 is "no floor, let lane quantisation alone bound S".
        assertThat(validator.validate(attentionWithFloor(1.0))).isEmpty();
        assertThat(validator.validate(attentionWithFloor(0.0))).isEmpty();
        assertThat(validator.validate(attentionWithFloor(0.25))).isEmpty();
        assertThat(validator.validate(attentionWithFloor(0.5))).isEmpty();

        // Negative is inert rather than harmful (max() never selects it), but it is not a
        // coherent probability-scale multiplier — the same rail rejects it so the constraint
        // asserts all of what it means, not half.
        assertThat(validator.validate(attentionWithFloor(-0.1))).isNotEmpty();
    }

    @Test
    void theSelfHealFloorRailIsSatisfiedByTheShippedDefault() {
        // The knob is nullable; an unset floor must not trip its own constraint.
        InspectorProperties.Attention defaults =
                new InspectorProperties.Triage(null, null, null, null).attentionOrDefault();

        assertThat(defaults.selfHealFloorOrDefault()).isEqualTo(0.25);
        assertThat(defaults.isSelfHealFloorAProbability()).isTrue();
        assertThat(validator.validate(defaults)).isEmpty();
        assertThat(validator.validate(attentionWithFloor(null))).isEmpty();
    }

    private static InspectorProperties.Attention attentionWithFloor(Double floor) {
        return new InspectorProperties.Attention(
                null, null, null, null, null, floor, null, null, null, null, null, null, null, null);
    }

    private static List<ConstraintViolation<EngineConfig>> violationsOn(EngineConfig engine, String property) {
        return validator.validate(engine).stream()
                .filter(v -> v.getPropertyPath().toString().equals(property))
                .toList();
    }

    /* ---------------- #476: fail-fast ranges. One case per new constraint. Null stays valid. -- */

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidKnobs")
    void rejectsOutOfRangeKnob(String name, String path, Object invalid, Object boundary) {
        assertThat(paths(invalid)).as(name).contains(path);
        assertThat(paths(boundary)).as(name).doesNotContain(path);
    }

    @Test
    void legalEdgesAndShippedDefaultsStayValid() {
        // Null components use OrDefault(). Zero stagger / circuit-pause is the supported "no wait".
        // A burst window shorter than model-ttl is a legal trade-off (startup WARN, not a refusal).
        // sample-interval PT1H with bucket-width PT15S is how the self-heal ITs idle the scheduler.
        // refresh-min-interval may be negative: the throttle comparison treats it as "always allow".
        assertThat(validator.validate(new InspectorProperties(null, null, null, null, null)))
                .isEmpty();
        assertThat(validator.validate(TestEngines.engine("ok-id", "http://localhost:8081/x")))
                .isEmpty();
        assertThat(validator.validate(new Triage(null, -1, 0, 200))).isEmpty();
        assertThat(validator.validate(new Bulk(4, 0, 0, 0))).isEmpty();
        assertThat(validator.validate(new Snapshot(null, Duration.ofHours(1), Duration.ofSeconds(15), null)))
                .isEmpty();
        assertThat(validator.validate(new Attention(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Duration.ofMinutes(5),
                        null,
                        null,
                        null,
                        Duration.ofMinutes(1),
                        null,
                        null,
                        null)))
                .isEmpty();
        assertThat(validator.validate(new AlarmThresholds(30, 5, 0))).isEmpty();
        assertThat(validator.validate(Auth.none())).isEmpty();
        assertThat(validator.validate(new SelfHeal(null, null, 2, 1))).isEmpty();
    }

    static Stream<Arguments> invalidKnobs() {
        String url = "http://localhost:8081/x";
        return Stream.of(
                knob(
                        "fanoutParallelism",
                        new InspectorProperties(0, null, null, null, null),
                        new InspectorProperties(10, null, null, null, null)),
                knob(
                        "hierarchyMaxDepth",
                        new InspectorProperties(null, 0, null, null, null),
                        new InspectorProperties(null, 1, null, null, null)),
                knob("cacheTtlS", new Triage(0, null, null, null), new Triage(20, null, null, null)),
                knob("stacktraceSampleCap", new Triage(null, null, -1, null), new Triage(null, null, 0, null)),
                knob("ackResurfaceThresholdPct", new Triage(null, null, null, -1), new Triage(null, null, null, 200)),
                knob(
                        "recencyHalfLife",
                        attention(
                                Duration.ZERO, null, null, null, null, null, null, null, null, null, null, null, null),
                        attention(
                                Duration.ofHours(24),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)),
                knob(
                        "arrivalsWindowDays",
                        attention(null, 0, null, null, null, null, null, null, null, null, null, null, null),
                        attention(null, 28, null, null, null, null, null, null, null, null, null, null, null)),
                knob(
                        "minClosedEpisodes",
                        attention(null, null, 0, null, null, null, null, null, null, null, null, null, null),
                        attention(null, null, 1, null, null, null, null, null, null, null, null, null, null)),
                knob(
                        "mttrClampLow",
                        attention(null, null, null, -0.1, null, null, null, null, null, null, null, null, null),
                        attention(null, null, null, 0.0, null, null, null, null, null, null, null, null, null)),
                knob(
                        "mttrClampHigh",
                        attention(null, null, null, null, -0.1, null, null, null, null, null, null, null, null),
                        attention(null, null, null, 0.0, 0.0, null, null, null, null, null, null, null, null)),
                order(
                        "mttrClampLow<=mttrClampHigh",
                        "mttrClampLow",
                        attention(null, null, null, 3.0, 1.0, null, null, null, null, null, null, null, null),
                        attention(null, null, null, 2.0, 2.0, null, null, null, null, null, null, null, null)),
                knob(
                        "modelTtl",
                        attention(
                                null, null, null, null, null, Duration.ZERO, null, null, null, null, null, null, null),
                        attention(
                                null,
                                null,
                                null,
                                null,
                                null,
                                Duration.ofMinutes(5),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)),
                knob(
                        "resurfaceFloorPct",
                        attention(null, null, null, null, null, null, -1, null, null, null, null, null, null),
                        attention(null, null, null, null, null, null, 150, null, null, null, null, null, null)),
                knob(
                        "resurfaceFalseBudgetPer30AckDays",
                        attention(null, null, null, null, null, null, null, -0.1, null, null, null, null, null),
                        attention(null, null, null, null, null, null, null, 0.0, null, null, null, null, null)),
                knob(
                        "burstWindow",
                        attention(
                                null, null, null, null, null, null, null, null, Duration.ZERO, null, null, null, null),
                        attention(
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                Duration.ofMinutes(10),
                                null,
                                null,
                                null,
                                null)),
                knob(
                        "burstOnset",
                        attention(null, null, null, null, null, null, null, null, null, 0, null, null, null),
                        attention(null, null, null, null, null, null, null, null, null, 10, null, null, null)),
                knob(
                        "burstExit",
                        attention(null, null, null, null, null, null, null, null, null, null, 0, null, null),
                        attention(null, null, null, null, null, null, null, null, null, null, 5, null, null)),
                knob(
                        "burstWeight",
                        attention(null, null, null, null, null, null, null, null, null, null, null, 0.0, null),
                        attention(null, null, null, null, null, null, null, null, null, null, null, 8.0, null)),
                knob(
                        "selfHealHorizon",
                        attention(
                                null, null, null, null, null, null, null, null, null, null, null, null, Duration.ZERO),
                        attention(
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                Duration.ofHours(1))),
                knob("enginePermits", new Bulk(0, null, null, null), new Bulk(4, null, null, null)),
                knob("staggerMs", new Bulk(null, -1, null, null), new Bulk(null, 0, null, null)),
                knob("circuitPauseMaxMs", new Bulk(null, null, -1, null), new Bulk(null, null, 0, null)),
                knob("circuitPausePollMs", new Bulk(null, null, null, -1), new Bulk(null, null, null, 0)),
                knob(
                        "sseSubscriberCap",
                        new Bulk(null, null, null, null, -1, null),
                        new Bulk(null, null, null, null, 0, null)),
                knob(
                        "sseCoalesceMs",
                        new Bulk(null, null, null, null, null, -1),
                        new Bulk(null, null, null, null, null, 0)),
                knob(
                        "sampleInterval",
                        new Snapshot(null, Duration.ZERO, null, null),
                        new Snapshot(null, Duration.ofHours(1), Duration.ofSeconds(15), null)),
                knob(
                        "bucketWidth",
                        new Snapshot(null, null, Duration.ZERO, null),
                        new Snapshot(null, Duration.ofHours(1), Duration.ofSeconds(15), null)),
                order(
                        "snapshot.retentionDays",
                        "retentionDays",
                        new Snapshot(null, null, null, 0),
                        new Snapshot(null, null, null, 400)),
                knob(
                        "quietWindow",
                        new Incidents(null, Duration.ZERO, null, null, null),
                        new Incidents(null, Duration.ofHours(24), null, null, null)),
                knob(
                        "regressionMinCount",
                        new Incidents(null, null, 0, null, null),
                        new Incidents(null, null, 1, null, null)),
                order(
                        "incidents.retentionDays",
                        "retentionDays",
                        new Incidents(null, null, null, 0, null),
                        new Incidents(null, null, null, 400, null)),
                knob("listCap", new Incidents(null, null, null, null, 0), new Incidents(null, null, null, null, 1)),
                knob("windowDays", new SelfHeal(null, 0, null, null), new SelfHeal(null, 90, null, null)),
                knob("floor", new SelfHeal(null, null, 0, null), new SelfHeal(null, null, 1, null)),
                knob("dwellCycles", new SelfHeal(null, null, null, 0), new SelfHeal(null, null, null, 1)),
                knob(
                        "maxPageSize",
                        TestEngines.builder("ok-id", url).maxPageSize(0).build(),
                        TestEngines.builder("ok-id", url).maxPageSize(2).build()),
                knob(
                        "dlqScanCap",
                        TestEngines.builder("ok-id", url).dlqScanCap(0).build(),
                        TestEngines.builder("ok-id", url).dlqScanCap(50).build()),
                knob(
                        "deepPagingMaxDepth",
                        TestEngines.builder("ok-id", url).deepPagingMaxDepth(0).build(),
                        TestEngines.builder("ok-id", url).deepPagingMaxDepth(6).build()),
                knob("connectMs", new Timeouts(0, null, null), new Timeouts(1000, null, null)),
                knob("readMs", new Timeouts(null, 0, null), new Timeouts(null, 1000, null)),
                knob("writeMs", new Timeouts(null, null, 0), new Timeouts(null, 1000, null)),
                knob("overdueTimerGraceS", new AlarmThresholds(null, null, -1), new AlarmThresholds(null, null, 0)),
                knob("type", new Auth(null, null, null, null, null, null, null, null, null, null, null), Auth.none()));
    }

    private static Arguments knob(String path, Object invalid, Object boundary) {
        return order(path, path, invalid, boundary);
    }

    private static Arguments order(String name, String path, Object invalid, Object boundary) {
        return Arguments.of(name, path, invalid, boundary);
    }

    private static Attention attention(
            Duration recency,
            Integer arrivals,
            Integer minClosed,
            Double clampLow,
            Double clampHigh,
            Duration modelTtl,
            Integer floorPct,
            Double budget,
            Duration burstWindow,
            Integer onset,
            Integer exit,
            Double weight,
            Duration horizon) {
        return new Attention(
                recency,
                arrivals,
                minClosed,
                clampLow,
                clampHigh,
                null,
                modelTtl,
                null,
                floorPct,
                budget,
                burstWindow,
                onset,
                exit,
                weight,
                horizon);
    }

    private static List<String> paths(Object bean) {
        return validator.validate(bean).stream()
                .map(v -> v.getPropertyPath().toString())
                .toList();
    }
}
