package io.inspector.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.inspector.support.NoDbTestSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Rung 3: the documented sign-in variable {@code INSPECTOR_DEV_PASSWORD} reaches
 * {@code inspector.security.dev-password}. Without the mapping a deploy that set it still signed
 * in with the default "dev".
 */
@SpringBootTest(properties = "INSPECTOR_DEV_PASSWORD=alias-pw-1")
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
@AutoConfigureMockMvc
class DevPasswordEnvAliasSpringTest {

    @Autowired
    MockMvc mvc;

    @ParameterizedTest
    @CsvSource({"admin,alias-pw-1,200", "viewer,alias-pw-1,200", "admin,dev,401", "viewer,dev,401"})
    void theDocumentedVariableSetsTheSignInPassword(String user, String password, int expected) throws Exception {
        int status = mvc.perform(get("/api/me").with(httpBasic(user, password)))
                .andReturn()
                .getResponse()
                .getStatus();
        assertThat(status).as(user + " with " + password).isEqualTo(expected);
    }
}
