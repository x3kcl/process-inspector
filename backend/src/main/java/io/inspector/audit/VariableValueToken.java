package io.inspector.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The compare-and-set token for a MASKED variable (#742, SPEC R-AUD-11): the masked read cannot
 * hand the SPA the old value to send back as {@code expectedOldValue}, so it hands an opaque
 * token instead and the CAS compares tokens server-side.
 *
 * <p>Keyed HMAC-SHA256, never a bare hash — a bare hash of a short secret (a PIN) is brute-forced
 * offline in seconds. The key is random per BFF process and never leaves it: a token reveals
 * nothing about the value, and one token cannot be compared across variables, instances or
 * engines (all three are bound in). A BFF restart invalidates outstanding tokens; the edit then
 * fails as an ordinary CAS conflict and the operator re-opens the editor.
 */
public final class VariableValueToken {

    private static final byte[] KEY = new byte[32];
    private static final ObjectMapper CANONICAL = new ObjectMapper();

    static {
        new SecureRandom().nextBytes(KEY);
    }

    private VariableValueToken() {}

    /** {@code scopeId} = the instance id, the execution id of a step-local, or the case id. */
    public static String issue(String engineId, String scopeId, String name, Object value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
            String input = engineId + '\0' + scopeId + '\0' + name + '\0' + CANONICAL.writeValueAsString(value);
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException | JsonProcessingException e) {
            throw new IllegalStateException("cannot issue a variable value token", e);
        }
    }

    /** Constant-time comparison of a presented token against the current value. */
    public static boolean matches(String token, String engineId, String scopeId, String name, Object current) {
        return token != null
                && MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.UTF_8),
                        issue(engineId, scopeId, name, current).getBytes(StandardCharsets.UTF_8));
    }
}
