package io.inspector.spike.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPIKE #492 S0 (NOT FOR MERGE) — the route guards (EngineScopedRouteGuardArchTest,
 * EngineRoleExpressionGuardArchTest) only walk {@code @RestController} classes. The MCP transport is a
 * {@code RouterFunction} bean and the tools are {@code @Component}s, so both guards pass VACUOUSLY with
 * a tool that iterates the registry (whoami_engines calls EngineRegistry.allForDisplay). This test pins
 * that blind spot: a build needs a sibling rule over {@code @McpTool} methods, not an exception entry.
 */
class McpSpikeArchCoverageTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.inspector");

    @Test
    void theRouteGuardsDoNotSeeMcpTools() {
        List<String> toolClasses = MAIN.stream()
                .filter(c -> c.getMethods().stream().anyMatch(m -> m.isAnnotatedWith(McpTool.class)))
                .map(c -> c.getSimpleName() + (c.isAnnotatedWith(RestController.class) ? "(@RestController)" : ""))
                .toList();
        System.out.println("SPIKE-ARCH @McpTool classes: " + toolClasses);
        assertThat(toolClasses).containsExactly("McpSpikeTools"); // present, and NOT a @RestController
        boolean iteratesRegistry = MAIN.get(McpSpikeTools.class).getMethodCallsFromSelf().stream()
                .anyMatch(c -> c.getTarget().getName().equals("allForDisplay"));
        assertThat(iteratesRegistry)
                .as("the tool is engine-scoped by the guards' own definition")
                .isTrue();
    }
}
