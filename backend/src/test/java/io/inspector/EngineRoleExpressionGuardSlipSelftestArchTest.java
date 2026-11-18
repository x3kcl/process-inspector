package io.inspector;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * #791 review: expressions that name an {@code @rbac} gate but still admit a caller without one (a
 * disjunction with a non-gate term, a negation, the gate inside a string literal), an open
 * meta-annotation and an open class-level expression are flagged; monotone gate combinations and a
 * fleet floor are not.
 */
class EngineRoleExpressionGuardSlipSelftestArchTest {

    @Test
    void slipsAreFlagged() {
        EngineRoleExpressionGuardArchTest.assertRoleExpressions(
                new ClassFileImporter().importPackages("guardslipfixture"),
                Set.of(
                        "SlipController.andTrue",
                        "SlipController.undefinedRole",
                        "SlipController.orTrue",
                        "SlipController.orHasRole",
                        "SlipController.negated",
                        "SlipController.inLiteral",
                        "SlipController.metaOpen",
                        "OpenClassController.inheritsOpen"));
    }
}
