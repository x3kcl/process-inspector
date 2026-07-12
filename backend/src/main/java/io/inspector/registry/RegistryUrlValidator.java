package io.inspector.registry;

import io.inspector.config.InspectorProperties.EngineEnvironment;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The SSRF guard for a runtime-registered engine base-URL (docs/REGISTRY-CRUD.md §5, R-OPS-13).
 * The base-URL is the ENTIRE attack surface: the BFF is a credential vault, so letting an admin
 * point it at {@code 169.254.169.254}, an internal admin plane, or the Postgres host is the single
 * most dangerous capability in the tool. This class runs on EVERY add/edit before persistence
 * ({@link #validate}) and is re-asserted against the PINNED ip at connection time
 * ({@link #isPinAllowed}) — belt and braces.
 *
 * <p>Pure and unwired in S1 (its only seam is a {@link HostResolver}); S3 wires it into the reload
 * path and the {@link io.inspector.client.GuardedCaller} connect path.
 *
 * <p>Rail order (canonicalize FIRST, cheap→expensive; nothing is dialled before {@code resolve}):
 * <ol>
 *   <li>canonicalize/parse (lowercase host, strip trailing dot, punycode, collapse {@code ..},
 *       explicit port) — a malformed URL is rejected before any other rail sees it;</li>
 *   <li>reject credentials embedded in the URL;</li>
 *   <li>scheme {@code http|https}, {@code https} required on prod;</li>
 *   <li>port allowlist (if the deploy pins one);</li>
 *   <li>egress allowlist (host glob, or — after resolution — every IP in an allowlisted CIDR);</li>
 *   <li>resolve, then reject if ANY resolved IP is internal (v4+v6 denylist), decoding hostile
 *       numeric encodings first; the dev escape lifts the denylist only for a dev engine whose
 *       address is explicitly inside an allowlisted CIDR;</li>
 *   <li>pin the first validated literal IP — the connection dials THAT ip with the original Host.</li>
 * </ol>
 *
 * <p>Validation-time rejections are specific (nothing was dialled — the rule name is safe copy);
 * probe/connect failures are coarse to the UI (enforced by callers, not here).
 */
public class RegistryUrlValidator {

    private static final int MAX_HOST_LENGTH = 253;

    private final HostResolver resolver;

    public RegistryUrlValidator() {
        this(HostResolver.system());
    }

    public RegistryUrlValidator(HostResolver resolver) {
        this.resolver = resolver;
    }

    /** Which rail rejected a base-URL — a machine key the UI maps to R-UXQ-05 copy. */
    public enum Rail {
        MALFORMED,
        CREDENTIALS_IN_URL,
        SCHEME,
        PORT,
        EGRESS_ALLOWLIST,
        ADDRESS_DENYLIST,
        UNRESOLVABLE
    }

    /** The outcome of validating a base-URL: an accepted pin, or a rail rejection with copy. */
    public sealed interface Result permits Pinned, Rejected {
        default boolean isAllowed() {
            return this instanceof Pinned;
        }
    }

    /**
     * An accepted base-URL. The connection MUST dial {@code pinnedIp} (validated) with
     * {@code Host: canonicalHost} — resolve-then-pin. Sibling contexts share the same host/pin.
     */
    public record Pinned(
            String scheme,
            String canonicalHost,
            int port,
            InetAddress pinnedIp,
            String canonicalBaseUrl,
            String externalJobApiBase,
            String cmmnApiBase)
            implements Result {}

    /** A rejected base-URL; {@code rail} is the machine key, {@code message} the operator copy. */
    public record Rejected(Rail rail, String message) implements Result {}

    public Result validate(String baseUrl, EngineEnvironment environment, RegistryEgressPolicy policy) {
        // ---- Rail 1: canonicalize / parse ----
        Canonical canonical;
        try {
            canonical = canonicalize(baseUrl);
        } catch (RejectedException e) {
            return e.rejected;
        }

        // ---- Rail 2: credentials in URL ----
        if (canonical.hadUserInfo) {
            return new Rejected(Rail.CREDENTIALS_IN_URL, "put credentials in auth, not the URL");
        }

        // ---- Rail 3: scheme ----
        String scheme = canonical.scheme;
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return new Rejected(Rail.SCHEME, "base-URL scheme must be http or https");
        }
        if (environment == EngineEnvironment.PROD && !scheme.equals("https")) {
            return new Rejected(Rail.SCHEME, "prod engines must use https");
        }

        // ---- Rail 4: port ----
        if (!policy.portAllowed(canonical.port)) {
            return new Rejected(Rail.PORT, "port " + canonical.port + " is not permitted by this deployment");
        }

        // ---- Rail 5/6: resolve, then egress-allowlist + address-denylist over every IP ----
        List<InetAddress> resolved;
        try {
            resolved = resolveHost(canonical.host);
        } catch (UnknownHostException e) {
            return new Rejected(Rail.UNRESOLVABLE, "base-URL host does not resolve");
        }
        if (resolved.isEmpty()) {
            return new Rejected(Rail.UNRESOLVABLE, "base-URL host does not resolve");
        }

        boolean hostGlobMatch = policy.hostMatchesGlob(canonical.host);
        boolean dev = environment == EngineEnvironment.DEV;
        for (InetAddress ip : resolved) {
            boolean inAllowedCidr = policy.anyCidrContains(ip);
            // Egress boundary: the name must be allowlisted OR every IP must sit in an allowed CIDR.
            if (!hostGlobMatch && !inAllowedCidr) {
                return new Rejected(
                        Rail.EGRESS_ALLOWLIST,
                        "host not in the egress allowlist — add it out-of-band or use a dev-scoped inspector");
            }
            // Internal-address denylist; the dev escape lifts it only for an explicitly allowed CIDR.
            if (RegistryAddresses.isInternal(ip) && !(dev && inAllowedCidr)) {
                return new Rejected(Rail.ADDRESS_DENYLIST, "host resolves to a private/internal address");
            }
        }

        InetAddress pinnedIp = resolved.get(0);
        return new Pinned(
                scheme,
                canonical.host,
                canonical.port,
                pinnedIp,
                canonical.baseUrl,
                siblingBase(canonical, "/external-job-api"),
                siblingBase(canonical, "/cmmn-api"));
    }

    /**
     * Connect-time re-check of an already-pinned IP (docs §5, "re-check the pinned IP … NEVER
     * re-resolve"). Re-resolving here would reopen the DNS-rebinding window the pin closes, so this
     * takes the literal IP and only re-applies the address denylist + dev escape. Called by the
     * {@link io.inspector.client.GuardedCaller} connect path in S3.
     */
    public boolean isPinAllowed(InetAddress pinnedIp, EngineEnvironment environment, RegistryEgressPolicy policy) {
        boolean inAllowedCidr = policy.anyCidrContains(pinnedIp);
        if (RegistryAddresses.isInternal(pinnedIp)) {
            return environment == EngineEnvironment.DEV && inAllowedCidr;
        }
        return true;
    }

    /* ---------- canonicalization ---------- */

    private record Canonical(String scheme, String host, int port, String path, String baseUrl, boolean hadUserInfo) {}

    /** Thrown internally to short-circuit canonicalization with a specific rail. */
    private static final class RejectedException extends RuntimeException {
        final Rejected rejected;

        RejectedException(Rail rail, String message) {
            super(message);
            this.rejected = new Rejected(rail, message);
        }
    }

    private Canonical canonicalize(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new RejectedException(Rail.MALFORMED, "base-URL is malformed");
        }
        URI uri;
        try {
            uri = new URI(baseUrl.strip());
        } catch (URISyntaxException e) {
            throw new RejectedException(Rail.MALFORMED, "base-URL is malformed");
        }
        String scheme = uri.getScheme();
        String authority = uri.getRawAuthority();
        if (scheme == null || authority == null) {
            throw new RejectedException(Rail.MALFORMED, "base-URL is malformed");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);

        boolean hadUserInfo = false;
        int at = authority.indexOf('@');
        if (at >= 0) {
            hadUserInfo = true;
            authority = authority.substring(at + 1);
        }

        String hostPart;
        Integer explicitPort;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                throw new RejectedException(Rail.MALFORMED, "base-URL is malformed");
            }
            hostPart = authority.substring(0, close + 1); // keep brackets for a v6 literal
            String rest = authority.substring(close + 1);
            explicitPort = parsePort(rest.startsWith(":") ? rest.substring(1) : rest.isEmpty() ? null : rest);
            if (!rest.isEmpty() && !rest.startsWith(":")) {
                throw new RejectedException(Rail.MALFORMED, "base-URL is malformed");
            }
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                hostPart = authority.substring(0, colon);
                explicitPort = parsePort(authority.substring(colon + 1));
            } else {
                hostPart = authority;
                explicitPort = null;
            }
        }

        String host = normalizeHost(hostPart);
        if (host.isEmpty() || host.length() > MAX_HOST_LENGTH) {
            throw new RejectedException(Rail.MALFORMED, "base-URL is malformed");
        }

        int port = explicitPort != null ? explicitPort : ("https".equals(scheme) ? 443 : 80);

        String path = normalizePath(uri.getRawPath());
        String hostForUrl = hostPart.startsWith("[") ? host.startsWith("[") ? host : "[" + host + "]" : host;
        String base = scheme + "://" + hostForUrl + ":" + port + path;
        return new Canonical(scheme, host, port, path, base, hadUserInfo);
    }

    private static Integer parsePort(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            int p = Integer.parseInt(raw);
            if (p < 1 || p > 65535) {
                throw new RejectedException(Rail.MALFORMED, "base-URL is malformed");
            }
            return p;
        } catch (NumberFormatException e) {
            throw new RejectedException(Rail.MALFORMED, "base-URL is malformed");
        }
    }

    /** Lowercase, strip a trailing dot, and punycode (IDN) — the canonical form all rails see. */
    private static String normalizeHost(String raw) {
        String host = raw.strip().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            return host; // v6 literal — leave as-is for the parser
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        try {
            // No ALLOW_UNASSIGNED: an unassigned code point is a malformed host, not silently mapped.
            host = IDN.toASCII(host);
        } catch (IllegalArgumentException e) {
            throw new RejectedException(Rail.MALFORMED, "base-URL is malformed");
        }
        return host;
    }

    /** Collapse {@code .}/{@code ..} and duplicate slashes so a traversal can't sneak past siblings. */
    private static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "";
        }
        // URI.normalize handles ./.. ; do it under a dummy host so a bare path parses.
        String normalized = URI.create("http://h" + (rawPath.startsWith("/") ? rawPath : "/" + rawPath))
                .normalize()
                .getRawPath();
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.equals("/") ? "" : normalized;
    }

    /**
     * The {@code /external-job-api} and {@code /cmmn-api} sibling contexts, derived by the same
     * convention as {@link io.inspector.client.GuardedCaller} but from the CANONICAL base —
     * so a trailing-dot or {@code ..}-traversal host can't produce a sibling the validator never saw.
     */
    private static String siblingBase(Canonical c, String sibling) {
        String origin = c.baseUrl.substring(0, c.baseUrl.length() - c.path.length());
        String path = c.path;
        String siblingPath = path.endsWith("/service")
                ? path.substring(0, path.length() - "/service".length()) + sibling
                : path + sibling;
        return origin + siblingPath;
    }

    private List<InetAddress> resolveHost(String host) throws UnknownHostException {
        var literal = RegistryAddresses.parseLiteral(host);
        if (literal.isPresent()) {
            return List.of(literal.get());
        }
        List<InetAddress> out = new ArrayList<>();
        for (InetAddress ip : resolver.resolve(host)) {
            out.add(RegistryAddresses.unwrapV4Mapped(ip)); // a resolver could hand back a v4-mapped form
        }
        return out;
    }
}
