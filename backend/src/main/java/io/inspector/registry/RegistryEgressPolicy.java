package io.inspector.registry;

import java.net.InetAddress;
import java.util.List;
import java.util.Set;

/**
 * The deploy-config egress boundary for runtime-registered engines (docs/REGISTRY-CRUD.md §3/§5,
 * R-OPS-13). NOT editable in-app: an admin who could edit the allowlist that bounds them has no
 * allowlist. This is the app-layer twin of the R-OPS-07 network egress allowlist — defense in
 * depth, set out-of-band (env / mounted file), bound to {@code inspector.registry.egress-*} when
 * it is wired in S2/S4. In S1 it is a pure value passed straight into {@link RegistryUrlValidator}.
 *
 * <p>A registered base-URL is permitted only if its host matches a {@code hostGlob} OR every
 * resolved IP falls inside a {@code cidr}. The internal-address denylist is enforced regardless;
 * a {@code cidr} that names a private/loopback range only lifts the denylist for a {@code dev}
 * engine (the dev escape) — prod's strict list never lists those ranges, so it never can.
 *
 * @param hostGlobs   allowed host patterns, {@code *} matching one DNS label run (e.g.
 *                    {@code *.corp.example.com}). Case-insensitive, matched on the canonical host.
 * @param cidrs       allowed CIDR blocks (v4 or v6). Containment satisfies the allowlist AND, for
 *                    a dev engine, lifts the internal-address denylist for addresses inside it.
 * @param allowedPorts empty ⇒ any TCP port on an allowlisted host; non-empty ⇒ the port must be in it.
 */
public record RegistryEgressPolicy(List<HostGlob> hostGlobs, List<Cidr> cidrs, Set<Integer> allowedPorts) {

    public RegistryEgressPolicy {
        hostGlobs = List.copyOf(hostGlobs);
        cidrs = List.copyOf(cidrs);
        allowedPorts = Set.copyOf(allowedPorts);
    }

    /**
     * Build from a raw allowlist as it appears in deploy config: entries containing {@code /} are
     * CIDRs, everything else is a host glob. {@code allowedPorts} empty ⇒ any port.
     */
    public static RegistryEgressPolicy of(List<String> allowlist, Set<Integer> allowedPorts) {
        var globs = new java.util.ArrayList<HostGlob>();
        var cidrs = new java.util.ArrayList<Cidr>();
        for (String raw : allowlist) {
            String entry = raw.strip();
            if (entry.isEmpty()) continue;
            if (entry.contains("/")) {
                cidrs.add(Cidr.parse(entry));
            } else {
                globs.add(HostGlob.parse(entry));
            }
        }
        return new RegistryEgressPolicy(globs, cidrs, allowedPorts);
    }

    /** An empty policy permits nothing — every host is rejected by the allowlist rail. */
    public static RegistryEgressPolicy empty() {
        return new RegistryEgressPolicy(List.of(), List.of(), Set.of());
    }

    boolean hostMatchesGlob(String canonicalHost) {
        return hostGlobs.stream().anyMatch(g -> g.matches(canonicalHost));
    }

    boolean anyCidrContains(InetAddress ip) {
        return cidrs.stream().anyMatch(c -> c.contains(ip));
    }

    boolean portAllowed(int port) {
        return allowedPorts.isEmpty() || allowedPorts.contains(port);
    }

    /**
     * A host glob where {@code *} matches a single non-empty DNS label (no dots) and {@code **}
     * matches any run of labels. Anchored (whole-host) and case-insensitive.
     */
    public record HostGlob(java.util.regex.Pattern pattern) {
        public static HostGlob parse(String glob) {
            String lower = glob.strip().toLowerCase(java.util.Locale.ROOT);
            StringBuilder re = new StringBuilder("^");
            for (int i = 0; i < lower.length(); i++) {
                char c = lower.charAt(i);
                if (c == '*') {
                    if (i + 1 < lower.length() && lower.charAt(i + 1) == '*') {
                        re.append("[a-z0-9.-]*"); // ** — any label run
                        i++;
                    } else {
                        re.append("[a-z0-9-]+"); // * — one label
                    }
                } else if (c == '.') {
                    re.append("\\.");
                } else {
                    re.append(java.util.regex.Pattern.quote(String.valueOf(c)));
                }
            }
            re.append("$");
            return new HostGlob(java.util.regex.Pattern.compile(re.toString()));
        }

        boolean matches(String canonicalHost) {
            return pattern.matcher(canonicalHost).matches();
        }
    }
}
