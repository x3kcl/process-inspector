package io.inspector;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.concurrent.TimeUnit;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The anti-flakiness iron rule (CLAUDE.md / engine-harness skill): Thread.sleep in any
 * test is a hard failure. Awaitility with explicit bounds is the only wait primitive.
 */
@AnalyzeClasses(packages = "io.inspector", importOptions = ImportOption.OnlyIncludeTests.class)
class NoSleepInTestsArchTest {

    @ArchTest
    static final ArchRule NO_SLEEP_IN_TESTS = noClasses()
            .should().callMethod(Thread.class, "sleep", long.class)
            .orShould().callMethod(Thread.class, "sleep", long.class, int.class)
            .orShould().callMethodWhere(
                    target(owner(JavaClass.Predicates.assignableTo(TimeUnit.class)))
                            .and(target(name("sleep"))))
            .because("fixed sleeps make tests flaky — use Awaitility await().atMost(...) "
                    + "against real engine/BFF state (engine-harness skill)");
}
