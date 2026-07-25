package io.inspector.security.reauth;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.inspector.action.CorrectiveActionService;
import io.inspector.api.AdminAccessController;
import io.inspector.bulk.BulkJobService;
import io.inspector.migration.MigrationService;
import java.util.Set;
import org.springframework.security.core.Authentication;

/**
 * Drift guard for the dangerous-set re-auth gate (issue #295): a hand-maintained roster, NOT a
 * structurally-derived rule. {@code DangerousActionReauthGate.enforce()}'s call sites cannot be
 * discovered from a shared marker today — tier lives only on {@code ActionVerb} (a plain enum
 * {@code CorrectiveActionService} owns), so a non-{@code ActionVerb} dangerous surface like
 * {@code MigrationService}'s MIGRATE action (SPEC §5 tier-3, but never modeled as an
 * {@code ActionVerb}) has no property ArchUnit can key on to find it automatically. That gap is
 * exactly how #295 happened: migration was tier-3 by spec and by every doc, and simply had no
 * {@code reauth.enforce(auth)} call anywhere.
 *
 * <p>This is the deliberate, documented fallback the #295 plan calls for: a named, commented
 * list-based test over a real structural check (ArchUnit's {@code callMethod} condition), not a
 * hand-waved "trust the review" note. It catches the SAME failure mode #295 hit for any class
 * already on the roster (delete the {@code reauth.enforce(auth)} call from
 * {@code MigrationService.execute()} and this test goes red) — it just can't catch a BRAND NEW
 * dangerous surface that was never added to the roster in the first place. Adding one: add its
 * class here AND call {@code reauth.enforce(auth)} at verb intent (before reason/typed-token —
 * see {@code MigrationService.execute()} or {@code CorrectiveActionService.execute()} for the
 * ordering this test cannot itself verify; a slice test proves ordering).
 *
 * <p>Cross-check (issue #309, the ArchUnit iron-rules review): #309's plan explicitly defers to
 * whichever mechanism #295 lands, rather than building a second one — this class IS that
 * mechanism. If #309 later introduces a real marker (an annotation or interface all dangerous
 * surfaces implement), replace this roster with a structural rule keyed on it; until then, do not
 * add a second, competing "dangerous surface" registry anywhere else in the codebase.
 */
@AnalyzeClasses(packages = "io.inspector", importOptions = ImportOption.DoNotIncludeTests.class)
class DangerousActionReauthCoverageArchTest {

    /** The hand-maintained roster — see the class javadoc for why it can't be derived structurally. */
    private static final Set<Class<?>> DANGEROUS_SURFACES = Set.of(
            CorrectiveActionService.class, // tier>=3 ActionVerbs (RETRY excluded; see the verb tiers)
            BulkJobService.class, // bulk submit — gated ONCE, see CorrectiveActionService#executeBulkItem
            AdminAccessController.class, // group->scope mapping writes (add/remove/approve)
            MigrationService.class // instance migration execute() — issue #295's own fix
            );

    @ArchTest
    static final ArchRule EVERY_ROSTERED_DANGEROUS_SURFACE_CALLS_THE_REAUTH_GATE = classes()
            .that(new DescribedPredicate<JavaClass>("are on the hand-maintained dangerous-surface roster") {
                @Override
                public boolean test(JavaClass input) {
                    return DANGEROUS_SURFACES.stream()
                            .anyMatch(rostered -> rostered.getName().equals(input.getName()));
                }
            })
            .should()
            .callMethod(DangerousActionReauthGate.class, "enforce", Authentication.class)
            .because("every rostered dangerous surface must call DangerousActionReauthGate.enforce(auth) at verb"
                    + " intent (IDP-SECURITY.md §5, R-SAFE-07) — issue #295 found MigrationService silently"
                    + " missing it; this is the documented, hand-maintained fallback until #309 lands a real"
                    + " structural marker");
}
