package io.inspector.registry;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;

/**
 * IP-literal decoding and the internal-address denylist — the load-bearing half of the SSRF
 * validator (docs/REGISTRY-CRUD.md §5, "Address denylist (v4 + v6)"). The metadata IP has many
 * spellings that curl/glibc resolve to {@code 169.254.169.254} but a naïve string check misses:
 * decimal {@code 2852039166}, hex {@code 0xA9FEA9FE}, octal {@code 0251.0376.0251.0376}, the
 * 1/2/3-part shorthands, the v4-mapped-v6 form {@code ::ffff:169.254.169.254}, and a trailing dot.
 * We decode every one of them to its true {@link InetAddress} so the denylist judges the real
 * address, then reject it — a quiet allow of any encoding is a Sev1 (R-TEST-03).
 */
final class RegistryAddresses {

    private RegistryAddresses() {}

    /**
     * If {@code host} is an IP literal in ANY recognized encoding, decode it to its true address;
     * otherwise empty (it is a DNS hostname to be resolved). Never performs DNS.
     */
    static Optional<InetAddress> parseLiteral(String host) {
        if (host == null || host.isEmpty()) {
            return Optional.empty();
        }
        if (host.indexOf(':') >= 0) {
            // IPv6 (possibly v4-mapped). InetAddress.getByName does NOT do DNS for a colon form.
            return parseV6(host);
        }
        return parseV4Numeric(host);
    }

    /** True if the address is one the BFF must never dial (v4 + v6 internal ranges). */
    static boolean isInternal(InetAddress ip) {
        // Unwrap a v4-mapped v6 address (::ffff:a.b.c.d, incl. its hex spelling ::ffff:a9fe:a9fe,
        // which Java may hand back as an Inet6Address) so the metadata IP can't hide behind it.
        ip = unwrapV4Mapped(ip);
        if (ip.isAnyLocalAddress() // 0.0.0.0 / ::
                || ip.isLoopbackAddress() // 127/8 / ::1
                || ip.isLinkLocalAddress() // 169.254/16 / fe80::/10
                || ip.isSiteLocalAddress() // 10/8, 172.16/12, 192.168/16 (v4 site-local)
                || ip.isMulticastAddress()) {
            return true;
        }
        byte[] b = ip.getAddress();
        if (ip instanceof Inet4Address) {
            int b0 = b[0] & 0xFF;
            int b1 = b[1] & 0xFF;
            // RFC1918 172.16/12 (isSiteLocalAddress covers it, but be explicit), CGNAT 100.64/10,
            // and link-local metadata 169.254.169.254 (covered by isLinkLocalAddress, belt+braces).
            if (b0 == 10) return true;
            if (b0 == 172 && b1 >= 16 && b1 <= 31) return true;
            if (b0 == 192 && b1 == 168) return true;
            if (b0 == 169 && b1 == 254) return true; // link-local incl. metadata
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return true; // CGNAT 100.64/10
            return b0 == 0; // 0.0.0.0/8
        }
        if (ip instanceof Inet6Address v6) {
            // Any IPv6 form that EMBEDS a v4 (v4-compatible ::/96, NAT64 64:ff9b::/96, 6to4
            // 2002::/16) is judged on the embedded address — else 64:ff9b::a9fe:a9fe or
            // 2002:a9fe:a9fe:: would carry the metadata IP straight past the v6 checks.
            InetAddress embedded = embeddedV4(v6);
            if (embedded != null) {
                return isInternal(embedded);
            }
            int b0 = b[0] & 0xFF;
            if ((b0 & 0xFE) == 0xFC) return true; // fc00::/7 ULA
            return b0 == 0xFF; // ff00::/8 multicast (isMulticast covers, belt+braces)
        }
        return false;
    }

    /**
     * The v4 address embedded in an IPv6 transition form, or null if {@code v6} embeds none:
     * v4-compatible {@code ::a.b.c.d} (::/96), NAT64 well-known-prefix {@code 64:ff9b::a.b.c.d}
     * (bytes 12–15), and 6to4 {@code 2002:AABB:CCDD::} (bytes 2–5). The v4-MAPPED form
     * ({@code ::ffff:a.b.c.d}) is already collapsed by {@link #unwrapV4Mapped} before it reaches
     * here. All three are recursed through {@link #isInternal} so an embedded metadata/RFC1918 IP
     * is denied while a genuinely public embedded IP stays allowed.
     */
    private static InetAddress embeddedV4(Inet6Address v6) {
        byte[] b = v6.getAddress();
        boolean first10Zero = true;
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                first10Zero = false;
                break;
            }
        }
        // ::/96 v4-compatible (::/:: and ::1 are already caught by isAnyLocal/isLoopback above).
        if (first10Zero && b[10] == 0 && b[11] == 0) {
            return v4At(b, 12);
        }
        // NAT64 well-known prefix 64:ff9b::/96 with an all-zero mid.
        if ((b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B) {
            boolean midZero = true;
            for (int i = 4; i < 12; i++) {
                if (b[i] != 0) {
                    midZero = false;
                    break;
                }
            }
            if (midZero) {
                return v4At(b, 12);
            }
        }
        // 6to4 2002::/16 — the v4 is bytes 2–5.
        if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02) {
            return v4At(b, 2);
        }
        return null;
    }

    private static InetAddress v4At(byte[] b, int off) {
        try {
            return InetAddress.getByAddress(new byte[] {b[off], b[off + 1], b[off + 2], b[off + 3]});
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * If {@code ip} is a v4-mapped IPv6 address ({@code ::ffff:0:0/96}), return its embedded
     * Inet4Address; otherwise return it unchanged. Java collapses the dotted spelling on its own,
     * but the hex spelling ({@code ::ffff:a9fe:a9fe}) can survive as an Inet6Address — this closes
     * that gap so the mapped-metadata form always faces the v4 denylist.
     */
    static InetAddress unwrapV4Mapped(InetAddress ip) {
        if (!(ip instanceof Inet6Address)) {
            return ip;
        }
        byte[] b = ip.getAddress();
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) return ip;
        }
        if ((b[10] & 0xFF) != 0xFF || (b[11] & 0xFF) != 0xFF) {
            return ip;
        }
        try {
            return InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]});
        } catch (UnknownHostException e) {
            return ip;
        }
    }

    private static Optional<InetAddress> parseV6(String host) {
        String h = host;
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        // Strip a zone id (%eth0) — never dial a scoped address.
        int pct = h.indexOf('%');
        if (pct >= 0) {
            h = h.substring(0, pct);
        }
        if (!h.contains(":")) {
            return Optional.empty();
        }
        try {
            // getByName resolves a bracket/colon literal without DNS; unwrap a v4-mapped form.
            return Optional.of(unwrapV4Mapped(InetAddress.getByName(h)));
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }

    /**
     * glibc/inet_aton-style IPv4: 1–4 dot-separated parts, each decimal / {@code 0}-octal /
     * {@code 0x}-hex. 4 parts ⇒ a.b.c.d; 3 ⇒ a.b.(16-bit); 2 ⇒ a.(24-bit); 1 ⇒ (32-bit).
     * Returns empty (→ treat as hostname) unless EVERY part is a numeric token in range.
     */
    private static Optional<InetAddress> parseV4Numeric(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length < 1 || parts.length > 4) {
            return Optional.empty();
        }
        long[] vals = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Long v = parseNumericToken(parts[i]);
            if (v == null) {
                return Optional.empty(); // a non-numeric part ⇒ this is a hostname, not a literal
            }
            vals[i] = v;
        }
        long ipv4;
        switch (parts.length) {
            case 1 -> ipv4 = vals[0];
            case 2 -> {
                if (vals[0] > 0xFF || vals[1] > 0xFFFFFF) return Optional.empty();
                ipv4 = (vals[0] << 24) | vals[1];
            }
            case 3 -> {
                if (vals[0] > 0xFF || vals[1] > 0xFF || vals[2] > 0xFFFF) return Optional.empty();
                ipv4 = (vals[0] << 24) | (vals[1] << 16) | vals[2];
            }
            default -> {
                for (long v : vals) {
                    if (v > 0xFF) return Optional.empty();
                }
                ipv4 = (vals[0] << 24) | (vals[1] << 16) | (vals[2] << 8) | vals[3];
            }
        }
        if (ipv4 < 0 || ipv4 > 0xFFFFFFFFL) {
            return Optional.empty();
        }
        byte[] b = {
            (byte) (ipv4 >>> 24), (byte) (ipv4 >>> 16), (byte) (ipv4 >>> 8), (byte) ipv4,
        };
        try {
            return Optional.of(InetAddress.getByAddress(b));
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }

    /** A single IPv4 part: decimal, {@code 0x}-hex, or {@code 0}-leading octal. null = not numeric. */
    private static Long parseNumericToken(String token) {
        if (token.isEmpty()) {
            return null;
        }
        try {
            String t = token.toLowerCase(Locale.ROOT);
            long v;
            if (t.startsWith("0x")) {
                if (t.length() == 2) return null;
                v = Long.parseLong(t.substring(2), 16);
            } else if (t.length() > 1 && t.charAt(0) == '0') {
                v = Long.parseLong(t, 8);
            } else {
                v = Long.parseLong(t, 10);
            }
            return v < 0 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
