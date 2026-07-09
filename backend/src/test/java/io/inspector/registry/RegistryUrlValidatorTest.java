package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.registry.RegistryUrlValidator.Pinned;
import io.inspector.registry.RegistryUrlValidator.Rail;
import io.inspector.registry.RegistryUrlValidator.Rejected;
import io.inspector.registry.RegistryUrlValidator.Result;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Rung 1 (unit-test-patterns): the SSRF validator's rail behaviour, allowlist/dev-escape,
 * resolve-then-pin and sibling inheritance (docs/REGISTRY-CRUD.md §5). The exhaustive
 * no-bypass proof lives in {@link RegistryUrlHostileCorpusTest}; this file pins the
 * per-rail contract and the accepted-path shape.
 */
class RegistryUrlValidatorTest {

    /** A fixed-map resolver — never touches DNS, so rebinding and internal IPs are reproducible. */
    private static HostResolver stub(Map<String, List<String>> map) {
        return host -> {
            List<String> ips = map.get(host);
            if (ips == null) {
                throw new UnknownHostException(host);
            }
            return ips.stream().map(RegistryUrlValidatorTest::ip).toList();
        };
    }

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Rail railOf(Result r) {
        assertThat(r).isInstanceOf(Rejected.class);
        return ((Rejected) r).rail();
    }

    /* ---------- accepted path ---------- */

    @Test
    void accepts_public_host_matching_glob_and_pins_resolved_ip() {
        var v = new RegistryUrlValidator(stub(Map.of("orders.corp.example.com", List.of("93.184.216.34"))));
        var policy = RegistryEgressPolicy.of(List.of("*.corp.example.com"), Set.of());

        Result r = v.validate("http://orders.corp.example.com/flowable-rest/service", EngineEnvironment.TEST, policy);

        assertThat(r).isInstanceOf(Pinned.class);
        Pinned p = (Pinned) r;
        assertThat(p.canonicalHost()).isEqualTo("orders.corp.example.com");
        assertThat(p.port()).isEqualTo(80);
        assertThat(p.pinnedIp()).isEqualTo(ip("93.184.216.34"));
        assertThat(p.canonicalBaseUrl()).isEqualTo("http://orders.corp.example.com:80/flowable-rest/service");
    }

    @Test
    void sibling_bases_inherit_the_canonical_host_and_replace_trailing_service_context() {
        var v = new RegistryUrlValidator(stub(Map.of("orders.corp.example.com", List.of("93.184.216.34"))));
        var policy = RegistryEgressPolicy.of(List.of("*.corp.example.com"), Set.of());

        Pinned p = (Pinned)
                v.validate("https://orders.corp.example.com/flowable-rest/service", EngineEnvironment.TEST, policy);

        assertThat(p.externalJobApiBase())
                .isEqualTo("https://orders.corp.example.com:443/flowable-rest/external-job-api");
        assertThat(p.cmmnApiBase()).isEqualTo("https://orders.corp.example.com:443/flowable-rest/cmmn-api");
    }

    @Test
    void accepts_public_ip_inside_an_allowlisted_cidr_without_a_host_glob() {
        var v = new RegistryUrlValidator(stub(Map.of("engine.dc1", List.of("93.184.216.34"))));
        var policy = RegistryEgressPolicy.of(List.of("93.184.216.0/24"), Set.of());

        Result r = v.validate("http://engine.dc1/service", EngineEnvironment.TEST, policy);

        assertThat(r).isInstanceOf(Pinned.class);
    }

    @Test
    void normalizes_trailing_dot_and_punycode_host() {
        var v = new RegistryUrlValidator(stub(Map.of("xn--bcher-kva.example", List.of("93.184.216.34"))));
        var policy = RegistryEgressPolicy.of(List.of("**"), Set.of());

        // Unicode host with a trailing dot → punycode + dot stripped.
        Result r = v.validate("http://bücher.example./service", EngineEnvironment.TEST, policy);

        assertThat(r).isInstanceOf(Pinned.class);
        assertThat(((Pinned) r).canonicalHost()).isEqualTo("xn--bcher-kva.example");
    }

    /* ---------- scheme / credentials / malformed ---------- */

    @Test
    void rejects_http_on_prod() {
        var v = new RegistryUrlValidator(stub(Map.of("orders.corp.example.com", List.of("93.184.216.34"))));
        var policy = RegistryEgressPolicy.of(List.of("*.corp.example.com"), Set.of());

        assertThat(railOf(v.validate("http://orders.corp.example.com/service", EngineEnvironment.PROD, policy)))
                .isEqualTo(Rail.SCHEME);
    }

    @Test
    void rejects_non_http_schemes() {
        var v = new RegistryUrlValidator();
        var policy = RegistryEgressPolicy.of(List.of("**"), Set.of());

        assertThat(railOf(v.validate("file://169.254.169.254/etc", EngineEnvironment.TEST, policy)))
                .isEqualTo(Rail.SCHEME);
        assertThat(railOf(v.validate("gopher://169.254.169.254:70/x", EngineEnvironment.TEST, policy)))
                .isEqualTo(Rail.SCHEME);
    }

    @Test
    void rejects_credentials_in_url_before_resolving() {
        var v = new RegistryUrlValidator();
        var policy = RegistryEgressPolicy.of(List.of("**", "0.0.0.0/0"), Set.of());

        assertThat(railOf(v.validate("https://user:pass@orders.corp.example.com/x", EngineEnvironment.TEST, policy)))
                .isEqualTo(Rail.CREDENTIALS_IN_URL);
    }

    @Test
    void rejects_malformed_and_over_long_host() {
        var v = new RegistryUrlValidator();
        var policy = RegistryEgressPolicy.of(List.of("**"), Set.of());

        assertThat(railOf(v.validate("not a url", EngineEnvironment.TEST, policy)))
                .isEqualTo(Rail.MALFORMED);
        assertThat(railOf(v.validate("", EngineEnvironment.TEST, policy))).isEqualTo(Rail.MALFORMED);
        assertThat(railOf(v.validate("http://host:0/x", EngineEnvironment.TEST, policy)))
                .isEqualTo(Rail.MALFORMED);
        String longHost = "http://" + "a".repeat(300) + "/x";
        assertThat(railOf(v.validate(longHost, EngineEnvironment.TEST, policy))).isEqualTo(Rail.MALFORMED);
    }

    /* ---------- address denylist (decode proof) ---------- */

    @Test
    void denylists_v4_metadata_ip_even_via_decimal_encoding() {
        var v = new RegistryUrlValidator();
        // Exact host glob passes the egress rail so the DENYLIST is what must fire (env≠dev ⇒ no escape).
        var policy = RegistryEgressPolicy.of(List.of("2852039166"), Set.of());

        assertThat(railOf(v.validate("http://2852039166/latest/meta-data/", EngineEnvironment.TEST, policy)))
                .isEqualTo(Rail.ADDRESS_DENYLIST);
    }

    @Test
    void denylists_v6_internal_addresses() {
        var v = new RegistryUrlValidator();
        for (String host : List.of("[::1]", "[fe80::1]", "[fc00::1]", "[::]")) {
            var policy = RegistryEgressPolicy.of(List.of(host), Set.of());
            assertThat(railOf(v.validate("http://" + host + "/x", EngineEnvironment.TEST, policy)))
                    .as("v6 internal %s", host)
                    .isEqualTo(Rail.ADDRESS_DENYLIST);
        }
    }

    @Test
    void denylists_v4_mapped_v6_metadata_in_hex_spelling() {
        var v = new RegistryUrlValidator();
        var policy = RegistryEgressPolicy.of(List.of("[::ffff:a9fe:a9fe]"), Set.of());

        assertThat(railOf(v.validate("http://[::ffff:a9fe:a9fe]/x", EngineEnvironment.TEST, policy)))
                .isEqualTo(Rail.ADDRESS_DENYLIST);
    }

    @Test
    void rebinding_multi_record_is_rejected_when_any_resolved_ip_is_internal() {
        // Name resolves to a benign public IP AND the metadata IP — validating ALL records catches it.
        var v = new RegistryUrlValidator(stub(Map.of("evil.example", List.of("93.184.216.34", "169.254.169.254"))));
        var policy = RegistryEgressPolicy.of(List.of("**"), Set.of());

        assertThat(railOf(v.validate("http://evil.example/x", EngineEnvironment.TEST, policy)))
                .isEqualTo(Rail.ADDRESS_DENYLIST);
    }

    /* ---------- egress allowlist / dev escape / port ---------- */

    @Test
    void rejects_public_host_outside_the_allowlist() {
        var v = new RegistryUrlValidator(stub(Map.of("random.public", List.of("93.184.216.34"))));
        var policy = RegistryEgressPolicy.of(List.of("*.corp.example.com"), Set.of());

        assertThat(railOf(v.validate("http://random.public/x", EngineEnvironment.TEST, policy)))
                .isEqualTo(Rail.EGRESS_ALLOWLIST);
    }

    @Test
    void dev_escape_allows_loopback_only_when_env_is_dev_and_cidr_is_allowlisted() {
        var v = new RegistryUrlValidator(stub(Map.of("localhost", List.of("127.0.0.1"))));
        var policy = RegistryEgressPolicy.of(List.of("localhost", "127.0.0.0/8"), Set.of());

        // dev + explicit CIDR ⇒ allowed
        assertThat(v.validate("http://localhost:8081/service", EngineEnvironment.DEV, policy))
                .isInstanceOf(Pinned.class);
        // same request on test ⇒ denylist still bites (no escape off dev)
        assertThat(railOf(v.validate("http://localhost:8081/service", EngineEnvironment.TEST, policy)))
                .isEqualTo(Rail.ADDRESS_DENYLIST);
    }

    @Test
    void port_allowlist_is_enforced_when_the_deploy_pins_one() {
        var v = new RegistryUrlValidator(stub(Map.of("orders.corp.example.com", List.of("93.184.216.34"))));
        var policy = RegistryEgressPolicy.of(List.of("*.corp.example.com"), Set.of(8443));

        assertThat(railOf(v.validate("https://orders.corp.example.com:9999/x", EngineEnvironment.TEST, policy)))
                .isEqualTo(Rail.PORT);
        assertThat(v.validate("https://orders.corp.example.com:8443/x", EngineEnvironment.TEST, policy))
                .isInstanceOf(Pinned.class);
    }

    /* ---------- connect-time pin re-check (no re-resolve) ---------- */

    @Test
    void isPinAllowed_rechecks_the_pinned_ip_without_resolving() {
        var v = new RegistryUrlValidator();
        var strict = RegistryEgressPolicy.of(List.of(), Set.of());
        var devLoopback = RegistryEgressPolicy.of(List.of("127.0.0.0/8"), Set.of());

        assertThat(v.isPinAllowed(ip("93.184.216.34"), EngineEnvironment.TEST, strict))
                .isTrue();
        assertThat(v.isPinAllowed(ip("169.254.169.254"), EngineEnvironment.TEST, strict))
                .isFalse();
        assertThat(v.isPinAllowed(ip("127.0.0.1"), EngineEnvironment.DEV, devLoopback))
                .isTrue();
        assertThat(v.isPinAllowed(ip("127.0.0.1"), EngineEnvironment.TEST, devLoopback))
                .isFalse();
    }
}
