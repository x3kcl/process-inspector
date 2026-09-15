package io.inspector.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.support.TestEngines;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
}
