package io.inspector.security.mapping;

import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The ≥1/≥2-{@code ACCESS_ADMIN} boot invariant (IDP-SECURITY.md §6, ⚠️ DevOps). Under the
 * {@code oidc} profile — where the group→scope mapping is the authority — the resolved mapping
 * MUST contain at least one {@code ACCESS_ADMIN} group; otherwise the tool would boot into a silent
 * no-apex lock-out (every login → zero scope, nobody can administer access). An ApplicationRunner
 * that throws fails startup loudly with the remediation. Two independent {@code ACCESS_ADMIN}
 * groups are additionally required for mapping CRUD to be ENABLED (S4) — with exactly one, boot
 * proceeds but a WARN records that CRUD is off (a single coerced apex can't self-administer, so
 * fleet-grant removal + four-eyes have no independent approver).
 *
 * <p>Not active on the dev chain ({@code !oidc}): dev ladder users are ADMIN-global directly from
 * the in-memory store, so there is no mapping apex to require and no lock-out to prevent.
 */
@Component
@Profile("oidc")
@Order(2)
public class ApexInvariantChecker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApexInvariantChecker.class);

    private final MappingSource mappingSource;

    public ApexInvariantChecker(MappingSource mappingSource) {
        this.mappingSource = mappingSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> apexGroups = mappingSource.allFleetGrants().stream()
                .filter(r -> r.grant() == FleetGrant.ACCESS_ADMIN)
                .map(MappingSource.FleetGrantRow::group)
                .collect(Collectors.toUnmodifiableSet());

        if (apexGroups.isEmpty()) {
            throw new IllegalStateException(
                    "APEX INVARIANT VIOLATED: the group→scope mapping resolves NO ACCESS_ADMIN group. "
                            + "Refusing to boot into a silent no-apex lock-out (IDP-SECURITY.md §6). Remediation: set "
                            + "INSPECTOR_ACCESS_ADMIN_GROUP (inspector.security.mapping.access-admin-group) to a known "
                            + "IdP group — the always-available env-bootstrap floor — and/or seed an ACCESS_ADMIN grant.");
        }
        if (apexGroups.size() == 1) {
            log.warn(
                    "APEX invariant: exactly ONE ACCESS_ADMIN group ({}) resolves — mapping CRUD stays DISABLED "
                            + "(≥2 independent apex groups are required so fleet-grant changes have an independent "
                            + "four-eyes approver). Add a second ACCESS_ADMIN group to enable CRUD.",
                    apexGroups.iterator().next());
        } else {
            log.info(
                    "APEX invariant satisfied: {} independent ACCESS_ADMIN groups resolve — mapping CRUD enabled.",
                    apexGroups.size());
        }
    }
}
