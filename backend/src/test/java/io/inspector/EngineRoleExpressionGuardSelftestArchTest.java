package io.inspector;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * #791 selftest for {@link EngineRoleExpressionGuardArchTest}: the planted mutants in {@code
 * guardfixture} (outside the {@code io.inspector} component scan) are flagged, the genuinely gated
 * route is not.
 */
class EngineRoleExpressionGuardSelftestArchTest {

    @Test
    void plantedMutantsAreFlagged() {
        EngineRoleExpressionGuardArchTest.assertRoleExpressions(
                new ClassFileImporter().importPackages("guardfixture"),
                Set.of(
                        "MutantController.signedInOnly",
                        "MutantController.permitAllOnly",
                        "MutantController.roleOrSignedIn"));
    }
}
