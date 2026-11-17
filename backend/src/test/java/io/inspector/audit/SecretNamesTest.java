package io.inspector.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The secret-name rule (R-AUD-03, #741): long names match as a case-insensitive substring; short
 * names (auth, pin, pwd, session) match only as a whole word or a camelCase / snake_case /
 * kebab-case / digit-boundary SEGMENT. Decisions pinned here: {@code oauth} is ONE segment so it
 * does not match {@code auth}; {@code session} is a segment name, so {@code sessionCount} is a
 * known, accepted over-redaction (safe side) while {@code obsession} / {@code sessions2x}-style
 * substrings are not hit.
 */
class SecretNamesTest {

    static Stream<Arguments> table() {
        return Stream.of(
                // the eight pre-existing names, unchanged (substring)
                Arguments.of("password", true),
                Arguments.of("userPasswd", true),
                Arguments.of("clientSecret", true),
                Arguments.of("apiToken", true),
                Arguments.of("dbCredentials", true),
                Arguments.of("apikey", true),
                Arguments.of("my_api_key", true),
                Arguments.of("x-api-key", true),
                // new long names (substring)
                Arguments.of("authorization", true),
                Arguments.of("Authorization", true),
                Arguments.of("proxyAuthorizationHeader", true),
                Arguments.of("bearer", true),
                Arguments.of("bearerValue", true),
                Arguments.of("cookie", true),
                Arguments.of("Set-Cookie", true),
                Arguments.of("private_key", true),
                Arguments.of("privatekey", true),
                Arguments.of("sshPrivateKey", true),
                // short names: whole word / segment -> match
                Arguments.of("pin", true),
                Arguments.of("PIN", true),
                Arguments.of("userPin", true),
                Arguments.of("user_pin", true),
                Arguments.of("PIN_CODE", true),
                Arguments.of("x-pin", true),
                Arguments.of("pin2", true),
                Arguments.of("PINCode", true),
                Arguments.of("auth", true),
                Arguments.of("userAuth", true),
                Arguments.of("auth_header", true),
                Arguments.of("authToken", true),
                Arguments.of("pwd", true),
                Arguments.of("db_pwd", true),
                Arguments.of("session", true),
                Arguments.of("sessionId", true),
                Arguments.of("session_id", true),
                Arguments.of("sessionCount", true),
                Arguments.of("ÜserPin", true),
                // short names embedded in ordinary words -> no match
                Arguments.of("shipping", false),
                Arguments.of("opinion", false),
                Arguments.of("spinning", false),
                Arguments.of("author", false),
                Arguments.of("authorName", false),
                Arguments.of("authority", false),
                Arguments.of("oauth", false),
                Arguments.of("oauthClient", false),
                Arguments.of("obsession", false),
                Arguments.of("pwdx", false),
                Arguments.of("épin", false),
                // boundaries
                Arguments.of(null, false),
                Arguments.of("", false),
                Arguments.of("   ", false),
                Arguments.of("amount", false),
                Arguments.of("orderId", false));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("table")
    void builtInRule(String name, boolean secret) {
        assertThat(SecretNames.isSecret(name)).isEqualTo(secret);
    }

    @Test
    void additionsUseTheSegmentRuleAndNeverRemoveBuiltIns() {
        List<String> extra = List.of("iban", "Tan-Code");
        assertThat(SecretNames.matches("iban", extra)).isTrue();
        assertThat(SecretNames.matches("customerIban", extra)).isTrue();
        assertThat(SecretNames.matches("tan_code", extra)).isFalse(); // "tan-code" is one addition with a separator
        assertThat(SecretNames.matches("ibanx", extra)).isFalse();
        assertThat(SecretNames.matches("bibanto", extra)).isFalse();
        assertThat(SecretNames.matches("password", extra)).isTrue();
        assertThat(SecretNames.matches("pin", List.of())).isTrue();
    }
}
