package io.inspector.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * Drift guard for issue #309 (candidate 1, the review's top pick — genuinely structurally
 * expressible): every method mapped to a mutating HTTP verb in {@code io.inspector.api..} must
 * carry {@code @PreAuthorize}, at either the method or the controller class (several controllers
 * — {@link AdminAccessController}, {@link AdminEnginesController} — gate the whole class once
 * instead of annotating every handler; both styles satisfy the corrective-actions rail).
 *
 * <p>ONE named, in-rule-documented exception, spiked before landing this rule (never a
 * hand-maintained list — see the RestClient rule in this same PR for the identical discipline):
 * {@link SearchController#search} is a {@code @PostMapping} used only to carry a filter body over
 * the wire, not a state mutation — it is a VIEWER-floor READ gated by {@link
 * io.inspector.security.ReadScopeGate} (per-caller engine visibility), not an RBAC role floor. Its
 * sibling {@code PersonTaskSearchController} (a {@code @GetMapping}, so never a false positive
 * here) documents the identical pattern in its own class javadoc. Do not add further exceptions
 * without the same explicit spike-and-document treatment — a growing list here is the rule
 * quietly becoming unenforced.
 */
@AnalyzeClasses(packages = "io.inspector.api", importOptions = ImportOption.DoNotIncludeTests.class)
class MutatingEndpointRbacArchTest {

    @ArchTest
    static final ArchRule EVERY_MUTATING_ENDPOINT_CARRIES_PREAUTHORIZE = methods()
            .that(new DescribedPredicate<JavaMethod>("are mapped to a mutating HTTP verb (POST/PUT/DELETE/PATCH)") {
                @Override
                public boolean test(JavaMethod method) {
                    return method.isAnnotatedWith(PostMapping.class)
                            || method.isAnnotatedWith(PutMapping.class)
                            || method.isAnnotatedWith(DeleteMapping.class)
                            || method.isAnnotatedWith(PatchMapping.class);
                }
            })
            .and(new DescribedPredicate<JavaMethod>("are not the documented SearchController.search exception") {
                @Override
                public boolean test(JavaMethod method) {
                    return !(method.getOwner().isEquivalentTo(SearchController.class)
                            && method.getName().equals("search"));
                }
            })
            .should(new ArchCondition<JavaMethod>("carry @PreAuthorize at method or controller-class level") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    boolean gated = method.isAnnotatedWith(PreAuthorize.class)
                            || method.getOwner().isAnnotatedWith(PreAuthorize.class);
                    if (!gated) {
                        events.add(SimpleConditionEvent.violated(
                                method,
                                method.getFullName()
                                        + " is mapped to a mutating HTTP verb without @PreAuthorize at either"
                                        + " method or class level"));
                    }
                }
            })
            .because("every mutating endpoint must be RBAC-gated (corrective-actions skill §2; CLAUDE.md iron rule"
                    + " 'every mutating endpoint follows ALL the corrective-actions rails') — a missing"
                    + " @PreAuthorize is Sev1 per TEST-STRATEGY §3, and issue #309's review found this exact drift"
                    + " already happening once per rail in three unrelated call sites");
}
