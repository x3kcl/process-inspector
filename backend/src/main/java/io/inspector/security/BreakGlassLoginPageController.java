package io.inspector.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * GET /break-glass (issue #94): the ONE discoverable door to the sealed break-glass account
 * (POST /break-glass, wired in {@link SecurityConfig} only when configured). The SPA itself can
 * never surface this — every non-{@code /api} path is {@code authenticated()}, so an
 * unauthenticated browser never gets far enough to load SPA JS before {@code oauth2Login}'s entry
 * point has already redirected it to the IdP, and there is no "IdP unreachable" event the SPA
 * could ever observe or react to (a down IdP fails as the browser's own native network error on
 * the IdP's foreign origin, never a response this app's own origin sees). RUNBOOK.md §4 already
 * documents this as a directly-known URL, not something auto-surfaced — this endpoint is what
 * makes that documented door real instead of a 404.
 *
 * <p>No JavaScript dependency either: the CSRF token rides a server-rendered hidden field (the
 * classic Spring Security form pattern), not the SPA's cookie-read/header-echo — a plain browser
 * can complete the whole flow.
 */
@RestController
public class BreakGlassLoginPageController {

    private final BreakGlassProperties breakGlass;
    private final String breakGlassPassword;

    public BreakGlassLoginPageController(
            BreakGlassProperties breakGlass, @Value("${INSPECTOR_BREAK_GLASS_PASSWORD:}") String breakGlassPassword) {
        this.breakGlass = breakGlass;
        this.breakGlassPassword = breakGlassPassword;
    }

    @GetMapping(value = "/break-glass", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> loginPage(HttpServletRequest request) {
        // Same gate as SecurityConfig's own POST wiring — an unconfigured deployment gets a plain
        // 404, identical to any other unmapped path, rather than revealing that break-glass exists
        // but isn't set up.
        if (!breakGlass.isEnabled() || breakGlassPassword == null || breakGlassPassword.isBlank()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        var csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        String hiddenField = csrf == null
                ? ""
                : "<input type=\"hidden\" name=\"" + HtmlUtils.htmlEscape(csrf.getParameterName()) + "\" value=\""
                        + HtmlUtils.htmlEscape(csrf.getToken()) + "\">";
        String html = """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <title>Break-glass sign-in — Process Inspector</title>
                <style>
                  body { font-family: system-ui, sans-serif; max-width: 32rem; margin: 4rem auto; padding: 0 1rem; }
                  h1 { color: #7a1f1f; }
                  .warn { background: #fdecea; border: 1px solid #7a1f1f; padding: 0.75rem 1rem; border-radius: 4px; }
                  label { display: block; margin-top: 1rem; }
                  input[type=text], input[type=password] { width: 100%; padding: 0.4rem; box-sizing: border-box; }
                  button { margin-top: 1.5rem; padding: 0.5rem 1.5rem; }
                </style>
                </head>
                <body>
                <h1>Break-glass sign-in</h1>
                <p class="warn">Sealed emergency access for an identity-provider outage only — ADMIN-global,
                never a fleet grant, every action logged and alerted on login. Rotate the credential
                afterwards (RUNBOOK.md &sect;4).</p>
                <form method="post" action="/break-glass">
                {{CSRF_FIELD}}
                <label>Username <input type="text" name="username" autocomplete="username" autofocus></label>
                <label>Password <input type="password" name="password" autocomplete="current-password"></label>
                <button type="submit">Sign in</button>
                </form>
                <p><a href="/oauth2/authorization/oidc">&larr; Normal sign-in</a></p>
                </body>
                </html>
                """.replace("{{CSRF_FIELD}}", hiddenField);
        return ResponseEntity.ok(html);
    }
}
