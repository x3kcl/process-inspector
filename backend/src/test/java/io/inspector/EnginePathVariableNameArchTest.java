package io.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * #747 (R-SAFE-17): {@link EngineScopedRouteGuardArchTest} recognises an engine-scoped route by a
 * parameter NAMED {@code engineId}, and method security reads {@code #engineId} by parameter name. A
 * route that binds the {@code {engineId}} path segment to a differently named parameter
 * ({@code @PathVariable("engineId") String eid}) would slip past both, so every such route must
 * name its parameter {@code engineId}.
 */
class EnginePathVariableNameArchTest {

    @Test
    void everyEngineIdPathSegmentBindsToAParameterNamedEngineId() {
        Set<String> checked = new TreeSet<>();
        Set<String> violations = new TreeSet<>();
        for (JavaClass controller : new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.inspector")) {
            if (!controller.isAnnotatedWith(RestController.class)) {
                continue;
            }
            Class<?> type = controller.reflect();
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] prefixes = base == null || base.path().length == 0 ? new String[] {""} : base.path();
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping own = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (own == null) {
                    continue;
                }
                String[] paths = own.path().length == 0 ? new String[] {""} : own.path();
                boolean named = Arrays.stream(prefixes)
                        .flatMap(p -> Stream.of(paths).map(s -> p + s))
                        .anyMatch(p -> p.contains("{engineId}"));
                if (!named) {
                    continue;
                }
                String name = type.getSimpleName() + "." + method.getName();
                checked.add(name);
                if (Arrays.stream(method.getParameters())
                        .map(Parameter::getName)
                        .noneMatch("engineId"::equals)) {
                    violations.add(name);
                }
            }
        }
        assertThat(checked).contains("InstanceDetailController.vitals", "TaskBrowseController.identityLinks");
        assertThat(violations).isEmpty();
    }
}
