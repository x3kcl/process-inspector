package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.registry.RegistryUrlValidator.Rail;
import io.inspector.registry.RegistryUrlValidator.Rejected;
import io.inspector.registry.RegistryUrlValidator.Result;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * CI-GATING hostile-input corpus for the SSRF validator (docs/REGISTRY-CRUD.md §5/§13, R-TEST-03)
 * — the SSRF twin of the R-OPS-08 hostile-message fixture. A quiet allow of ANY case is a Sev1
 * ("guard bypass"): the primary assertion is {@code !isAllowed()} for every row.
 *
 * <p>The policy here is deliberately WIDE OPEN at the egress layer (host glob {@code **} plus the
 * {@code 0.0.0.0/0} / {@code ::/0} CIDRs) and the environment is {@code test} (http allowed, no dev
 * escape). That strips away every rail EXCEPT the ones under test — so a metadata IP that reaches
 * the address denylist proves the validator DECODED the encoding rather than being saved by a
 * strict allowlist it would have had in prod. Each row therefore also pins the exact rail it must
 * die on.
 *
 * <p>Redirect rejection is NOT exercised here: at validation time no request is made, so a 3xx is
 * impossible. That rail is {@code followRedirects(NEVER)} on the built client and is asserted in
 * the S3/S4 dockerized-engine IT, not this pure corpus.
 */
class RegistryUrlHostileCorpusTest {

    private static final RegistryEgressPolicy WIDE_OPEN =
            RegistryEgressPolicy.of(List.of("**", "0.0.0.0/0", "::/0"), Set.of());

    private static Arguments row(String label, String url, Rail expectedRail) {
        return Arguments.of(label, url, expectedRail);
    }

    static Stream<Arguments> hostileUrls() {
        return Stream.of(
                // ---- cloud-metadata 169.254.169.254 in every spelling → decoded, then denylisted ----
                row("dotted metadata", "http://169.254.169.254/latest/meta-data/", Rail.ADDRESS_DENYLIST),
                row("decimal metadata", "http://2852039166/latest/meta-data/", Rail.ADDRESS_DENYLIST),
                row("hex metadata", "http://0xA9FEA9FE/", Rail.ADDRESS_DENYLIST),
                row("uppercase-hex metadata", "http://0XA9FEA9FE/", Rail.ADDRESS_DENYLIST),
                row("octal metadata", "http://0251.0376.0251.0376/", Rail.ADDRESS_DENYLIST),
                row("3-part metadata", "http://169.254.43518/", Rail.ADDRESS_DENYLIST),
                row("2-part metadata", "http://169.16689150/", Rail.ADDRESS_DENYLIST),
                row("v4-mapped-v6 dotted", "http://[::ffff:169.254.169.254]/", Rail.ADDRESS_DENYLIST),
                row("v4-mapped-v6 hex", "http://[::ffff:a9fe:a9fe]/", Rail.ADDRESS_DENYLIST),
                row("v4-compatible v6", "http://[::a9fe:a9fe]/", Rail.ADDRESS_DENYLIST),
                row("nat64 wkp metadata", "http://[64:ff9b::a9fe:a9fe]/", Rail.ADDRESS_DENYLIST),
                row("6to4 rfc1918", "http://[2002:0a00:0001::]/", Rail.ADDRESS_DENYLIST),
                row("trailing-dot metadata host", "http://169.254.169.254./", Rail.ADDRESS_DENYLIST),
                row("traversal + trailing-dot host", "http://169.254.169.254./../../x", Rail.ADDRESS_DENYLIST),
                // ---- loopback in every numeric encoding (Gemini S1 review: prove the decode) ----
                row("decimal loopback", "http://2130706433/", Rail.ADDRESS_DENYLIST),
                row("hex loopback", "http://0x7f000001/", Rail.ADDRESS_DENYLIST),
                row("octal loopback", "http://0177.0.0.1/", Rail.ADDRESS_DENYLIST),
                row("short-form 2-part loopback", "http://127.1/", Rail.ADDRESS_DENYLIST),
                row("short-form 1-part loopback", "http://127/", Rail.ADDRESS_DENYLIST),
                // ---- other internal ranges (v4 + v6) ----
                row("loopback v4", "http://127.0.0.1/", Rail.ADDRESS_DENYLIST),
                row("loopback v6", "http://[::1]/", Rail.ADDRESS_DENYLIST),
                row("unspecified v4", "http://0.0.0.0/", Rail.ADDRESS_DENYLIST),
                row("unspecified v6", "http://[::]/", Rail.ADDRESS_DENYLIST),
                row("rfc1918 10/8", "http://10.0.0.5/", Rail.ADDRESS_DENYLIST),
                row("rfc1918 172.16/12", "http://172.16.0.1/", Rail.ADDRESS_DENYLIST),
                row("rfc1918 192.168/16", "http://192.168.1.1/", Rail.ADDRESS_DENYLIST),
                row("0/8 non-zero", "http://0.1.2.3/", Rail.ADDRESS_DENYLIST),
                row("cgnat 100.64/10", "http://100.100.100.100/", Rail.ADDRESS_DENYLIST),
                row("link-local v6", "http://[fe80::dead:beef]/", Rail.ADDRESS_DENYLIST),
                row("ula v6 fc00/7", "http://[fc00::1]/", Rail.ADDRESS_DENYLIST),
                row("multicast v6", "http://[ff02::1]/", Rail.ADDRESS_DENYLIST),
                // ---- non-http schemes ----
                row("file scheme", "file://169.254.169.254/etc/passwd", Rail.SCHEME),
                row("gopher scheme", "gopher://169.254.169.254:70/x", Rail.SCHEME),
                row("ftp scheme", "ftp://169.254.169.254/x", Rail.SCHEME),
                // ---- embedded credentials ----
                row("credential-in-url", "https://admin:secret@169.254.169.254/", Rail.CREDENTIALS_IN_URL),
                row(
                        "credential-in-url public",
                        "https://admin:secret@orders.corp.example.com/",
                        Rail.CREDENTIALS_IN_URL),
                // ---- malformed ----
                row("no authority", "file:///etc/passwd", Rail.MALFORMED),
                row("not a url", "http://", Rail.MALFORMED),
                row("over-long host", "http://" + "a".repeat(300) + "/x", Rail.MALFORMED));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("hostileUrls")
    void every_hostile_url_is_rejected_on_the_expected_rail(String label, String url, Rail expectedRail) {
        // Default (system) resolver: every row is decided before DNS (literal IP, or a pre-resolve rail).
        Result r = new RegistryUrlValidator().validate(url, EngineEnvironment.TEST, WIDE_OPEN);

        // The Sev1 gate: a quiet allow of ANY hostile URL is a guard bypass.
        assertThat(r.isAllowed()).as("%s (%s) must NOT be allowed", label, url).isFalse();
        assertThat(((Rejected) r).rail())
                .as("%s (%s) must die on the expected rail", label, url)
                .isEqualTo(expectedRail);
    }
}
