package io.inspector.api;

import io.inspector.action.ActionVerb;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The verb-existence check that makes the RBAC gate fail-closed WITHOUT losing the typo→404 UX
 * (IDP-SECURITY.md §3.10). {@code RbacAuthorizer.canExecute} now returns {@code false} for any
 * path that doesn't resolve to a known {@link ActionVerb} (was {@code .orElse(true)} — a
 * fail-OPEN default: a verb wired before its enum entry, a rename, or a sub-path would have
 * executed with no role check). Flipping to fail-closed alone would turn a genuine typo into a
 * misleading 403.
 *
 * <p>This interceptor runs in {@code preHandle} — <b>before</b> the {@code @PreAuthorize} method
 * advice — and answers 404 for an unknown {@code {verb}} path variable, so a typo dies as
 * "unknown verb" while a KNOWN-but-forbidden verb still gets a clean 403 from the fail-closed
 * gate. The authorization decision itself never defaults to allow.
 */
public class VerbExistenceInterceptor implements HandlerInterceptor {

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars instanceof Map<?, ?> map) {
            Object verb = ((Map<String, Object>) map).get("verb");
            if (verb != null && ActionVerb.fromPath(String.valueOf(verb)).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown verb: " + verb);
            }
        }
        return true;
    }
}
