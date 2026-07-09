package io.inspector.registry;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * A CIDR block (v4 or v6) with a byte-prefix containment test — the primitive behind both the
 * egress allowlist and the internal-address denylist (docs/REGISTRY-CRUD.md §5). No external IP
 * library is on the classpath (guava/commons-net absent by design), so this is a small,
 * self-contained prefix matcher over {@link InetAddress#getAddress()} raw bytes.
 *
 * <p>Containment is address-family strict: a v4 address is never contained by a v6 block and vice
 * versa. A v4-mapped v6 address ({@code ::ffff:a.b.c.d}) is normalized to its v4 form by
 * {@link RegistryAddresses} BEFORE it reaches here, so the mapped-metadata bypass is closed
 * upstream and this class only ever sees canonical v4/v6.
 */
public record Cidr(byte[] network, int prefixBits, int family) {

    public static Cidr parse(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("not a CIDR (missing /): " + cidr);
        }
        String host = cidr.substring(0, slash).strip();
        int prefix;
        try {
            prefix = Integer.parseInt(cidr.substring(slash + 1).strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad CIDR prefix: " + cidr, e);
        }
        InetAddress base;
        try {
            base = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("bad CIDR network: " + cidr, e);
        }
        byte[] bytes = base.getAddress();
        int max = bytes.length * 8;
        if (prefix < 0 || prefix > max) {
            throw new IllegalArgumentException("CIDR prefix out of range for family: " + cidr);
        }
        // Zero the host bits so a sloppy "10.1.2.3/8" still means 10.0.0.0/8.
        maskInPlace(bytes, prefix);
        return new Cidr(bytes, prefix, bytes.length);
    }

    public boolean contains(InetAddress ip) {
        byte[] a = ip.getAddress();
        if (a.length != family) {
            return false; // strict family match
        }
        byte[] masked = a.clone();
        maskInPlace(masked, prefixBits);
        return Arrays.equals(masked, network);
    }

    private static void maskInPlace(byte[] addr, int prefixBits) {
        for (int i = 0; i < addr.length; i++) {
            int bitsForByte = prefixBits - i * 8;
            if (bitsForByte >= 8) {
                continue; // whole byte kept
            } else if (bitsForByte <= 0) {
                addr[i] = 0; // whole byte zeroed
            } else {
                int mask = (0xFF << (8 - bitsForByte)) & 0xFF;
                addr[i] &= (byte) mask;
            }
        }
    }

    // Records auto-generate equals/hashCode, but the array field would compare by identity;
    // override so two equal CIDRs (same network bytes) are actually equal.
    @Override
    public boolean equals(Object o) {
        return o instanceof Cidr c
                && prefixBits == c.prefixBits
                && family == c.family
                && Arrays.equals(network, c.network);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(network) * 31 + prefixBits;
    }

    @Override
    public String toString() {
        try {
            return InetAddress.getByAddress(network).getHostAddress() + "/" + prefixBits;
        } catch (UnknownHostException e) {
            return Arrays.toString(network) + "/" + prefixBits;
        }
    }
}
