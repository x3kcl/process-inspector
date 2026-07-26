package io.inspector.client;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.inspector.security.mapping.AlertWebhookSender;
import org.springframework.web.client.RestClient;

/**
 * Drift guard for issue #309 (candidate 2): the F1 bypass class (docs/IMPROVEMENT-PLAN-2026-07.md)
 * was a URI-concat whitelist bypass reachable because a class outside {@code io.inspector.client..}
 * built its own engine caller instead of going through {@link GuardedCaller} (the BFF's ONE engine
 * chokepoint — CLAUDE.md "the BFF whitelists engine paths; no generic proxy route, ever"). This
 * rule pins that: nothing outside {@code io.inspector.client..} may depend on {@link RestClient}
 * directly.
 *
 * <p>Spiked first (per the issue's expressibility gate) with a repo-wide grep for every real
 * {@code RestClient} reference — see this PR's description for the full inventory. Exactly ONE
 * legitimate exception exists today: {@link AlertWebhookSender}
 * ({@code io.inspector.security.mapping}) builds its own standalone {@code RestClient} to fire
 * break-glass/access-change alerts at an operator-configured webhook (Slack/PagerDuty) — it is not
 * talking to any registered Flowable engine, so routing it through {@link GuardedCaller} (which is
 * keyed on engine ids, circuit breakers, and bulkheads per registered engine) makes no sense. This
 * is a single, named, documented exception — not a hand-maintained list that grows quietly; adding
 * a second exception here without the same spike-and-document treatment defeats the rule.
 */
@AnalyzeClasses(packages = "io.inspector", importOptions = ImportOption.DoNotIncludeTests.class)
class RestClientWhitelistArchTest {

    @ArchTest
    static final ArchRule ONLY_THE_CLIENT_PACKAGE_TOUCHES_REST_CLIENT = noClasses()
            .that()
            .resideOutsideOfPackage("io.inspector.client..")
            .and(
                    new com.tngtech.archunit.base.DescribedPredicate<JavaClass>(
                            "are not the documented AlertWebhookSender exception") {
                        @Override
                        public boolean test(JavaClass input) {
                            return !input.isEquivalentTo(AlertWebhookSender.class);
                        }
                    })
            .should()
            .dependOnClassesThat()
            .areAssignableTo(RestClient.class)
            .because("engine calls must go through GuardedCaller's whitelisted chokepoint"
                    + " (io.inspector.client..) — CLAUDE.md 'the BFF whitelists engine paths; no generic"
                    + " proxy route, ever'; a second class building its own RestClient is exactly how the F1"
                    + " URI-concat whitelist bypass (docs/IMPROVEMENT-PLAN-2026-07.md) happened");
}
