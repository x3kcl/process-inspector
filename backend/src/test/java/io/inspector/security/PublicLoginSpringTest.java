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
 * Rung 3: public demo logins through the real dev filter chain. A public user signs in with the
 * public password and NOT the private one; every other user the other way round.
 */
@SpringBootTest(
        properties = {
            "inspector.security.dev-password=private-pw-1",
            "inspector.security.public-password=public-pw-1",
            "inspector.security.public-users=viewer,responder"
        })
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
@AutoConfigureMockMvc
class PublicLoginSpringTest {

    @Autowired
    MockMvc mvc;

    @ParameterizedTest
    @CsvSource({
        "viewer,public-pw-1,200",
        "viewer,private-pw-1,401",
        "responder,public-pw-1,200",
        "responder,private-pw-1,401",
        "operator,private-pw-1,200",
        "operator,public-pw-1,401",
        "admin,private-pw-1,200",
        "admin,public-pw-1,401",
        "registry-admin,public-pw-1,401",
        "access-admin,public-pw-1,401",
        "viewer,dev,401",
        "admin,dev,401"
    })
    void eachUserSignsInWithItsOwnPasswordOnly(String user, String password, int expected) throws Exception {
        int status = mvc.perform(get("/api/me").with(httpBasic(user, password)))
                .andReturn()
                .getResponse()
                .getStatus();
        assertThat(status).as(user + " with " + password).isEqualTo(expected);
    }
}
