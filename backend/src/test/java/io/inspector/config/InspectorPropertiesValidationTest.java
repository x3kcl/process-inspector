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

    private static List<ConstraintViolation<EngineConfig>> violationsOn(EngineConfig engine, String property) {
        return validator.validate(engine).stream()
                .filter(v -> v.getPropertyPath().toString().equals(property))
                .toList();
    }
}
