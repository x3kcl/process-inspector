package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

class ProblemCodesTest {

    @Test
    void slugifiesKnownStatusesToKebabCase() {
        assertThat(ProblemCodes.fromStatus(HttpStatus.BAD_REQUEST)).isEqualTo("bad-request");
        assertThat(ProblemCodes.fromStatus(HttpStatus.NOT_FOUND)).isEqualTo("not-found");
        assertThat(ProblemCodes.fromStatus(HttpStatus.FORBIDDEN)).isEqualTo("forbidden");
        assertThat(ProblemCodes.fromStatus(HttpStatus.INTERNAL_SERVER_ERROR)).isEqualTo("internal-server-error");
    }

    @Test
    void fallsBackToTheNumericStatusWhenUnresolvable() {
        assertThat(ProblemCodes.fromStatus(HttpStatusCode.valueOf(499))).isEqualTo("499");
    }
}
