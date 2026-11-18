package io.inspector.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * #784: {@code inspector.heat.*} is refused at boot outside its range (the same fail-fast
 * {@code @Validated} binding as every other {@code inspector.*} block). Owner decision 1 on #671:
 * default sample 200, clamp 1 000 — configuration may lower the clamp, never raise it.
 */
class HeatPropertiesTest {

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

    @Test
    void defaultsAre200And1000() {
        HeatProperties props = new HeatProperties(null, null);

        assertThat(props.sampleSizeOrDefault()).isEqualTo(200);
        assertThat(props.maxSampleSizeOrDefault()).isEqualTo(1000);
        assertThat(validator.validate(props)).isEmpty();
    }

    @Test
    void theBoundsThemselvesAreAccepted() {
        assertThat(validator.validate(new HeatProperties(1, 1))).isEmpty();
        assertThat(validator.validate(new HeatProperties(1000, 1000))).isEmpty();
    }

    @Test
    void aSampleSizeBelowOneIsRefused() {
        assertThat(validator.validate(new HeatProperties(0, null))).hasSize(1);
    }

    @Test
    void aClampAboveTheOwnerDecidedThousandIsRefused() {
        assertThat(validator.validate(new HeatProperties(null, 1001))).isNotEmpty();
    }

    @Test
    void aClampBelowOneIsRefused() {
        assertThat(validator.validate(new HeatProperties(null, 0))).isNotEmpty();
    }

    @Test
    void aDefaultAboveTheClampIsRefused() {
        assertThat(validator.validate(new HeatProperties(300, 200))).hasSize(1);
        assertThat(validator.validate(new HeatProperties(null, 199))).hasSize(1);
    }
}
