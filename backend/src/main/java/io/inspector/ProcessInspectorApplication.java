package io.inspector;

import java.security.Security;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ProcessInspectorApplication {

    public static void main(String[] args) {
        // Without a SecurityManager (the default, and the only option since JEP 486 removed it),
        // the JDK caches a SUCCESSFUL DNS resolution FOREVER for the process's lifetime — a separate
        // cache layered ABOVE any java.net.spi.InetAddressResolverProvider (Registry S4b, #91,
        // PinnedAddressResolverProvider). Left at the default, the registry's "re-check the pinned IP
        // against the CURRENT egress policy on every connect" guarantee would silently only hold for
        // each hostname's FIRST-EVER resolution in this JVM's life — every later admin re-pin
        // (edit/probe/reload) would be masked by the stale cache entry and never actually reach the
        // resolver again. MUST be set before the first DNS lookup anywhere in the process (Postgres,
        // OIDC, engines, …), hence the first line of main().
        Security.setProperty("networkaddress.cache.ttl", "0");
        SpringApplication.run(ProcessInspectorApplication.class, args);
    }
}
