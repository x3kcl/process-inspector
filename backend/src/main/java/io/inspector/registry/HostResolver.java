package io.inspector.registry;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * The DNS seam for {@link RegistryUrlValidator} (S1 SSRF validator, docs/REGISTRY-CRUD.md §5).
 *
 * <p>Abstracted so the hostile-input corpus can drive rebinding and fixed-IP scenarios without
 * real DNS — a stub maps {@code evil.com} to a metadata IP, proving the validator judges the
 * RESOLVED addresses, not the name it was handed. The production implementation
 * ({@link #system()}) resolves through {@link InetAddress#getAllByName}. Resolution happens
 * exactly ONCE per validation (resolve-then-pin): the validated literal IP is pinned and the
 * actual connection re-checks the PINNED ip, never re-resolves — that is what closes the
 * DNS-rebinding TOCTOU window.
 */
@FunctionalInterface
public interface HostResolver {

    /** All A/AAAA records for {@code host}; never empty on success. */
    List<InetAddress> resolve(String host) throws UnknownHostException;

    /** The real resolver — the system DNS. */
    static HostResolver system() {
        return host -> List.of(InetAddress.getAllByName(host));
    }
}
