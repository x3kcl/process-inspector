package io.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.ReadScopeGate;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * #752 (R-SAFE-17): the stricter sibling of {@link EngineScopedRouteGuardArchTest}. A per-engine
 * route must carry a ROLE expression ({@code @PreAuthorize}, method or class) — the read-scope
 * gate alone is a no-op with {@code scope-reads-enforced} off, leaving the route open to any
 * signed-in user. Routes that still lack one are named in {@link #EXCEPTIONS} with the reason; a
 * NEW route cannot join them silently, and a fixed one must leave the list (no stale entries).
 */
class EngineRoleExpressionGuardArchTest {

    static final Map<String, String> EXCEPTIONS = Map.ofEntries(Map.entry(
            "MeController.me",
            "lists the caller's OWN role per engine (same exception as EngineScopedRouteGuardArchTest)"));

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.inspector");

    @Test
    void everyEngineScopedRouteCarriesARoleExpression() {
        assertRoleExpressions(MAIN, EXCEPTIONS.keySet());
    }

    /** The routes of {@code classes} lacking a role expression are exactly {@code expected}. */
    static void assertRoleExpressions(JavaClasses classes, Set<String> expected) {
        Map<String, String> noRole = new TreeMap<>();
        for (JavaClass controller : classes) {
            if (!controller.isAnnotatedWith(RestController.class)) {
                continue;
            }
            for (JavaMethod route : controller.getMethods()) {
                if (!isRoute(route)) {
                    continue;
                }
                Set<String> calls = reachableCalls(controller, route);
                boolean iterates = calls.contains(EngineRegistry.class.getName() + ".all")
                        || calls.contains(EngineRegistry.class.getName() + ".allForDisplay");
                // A route that consults the read scope (ReadScopeGate itself, or a service holding it)
                // reads per-engine data even when it names no engine and iterates no registry.
                boolean scoped = calls.stream()
                        .map(c -> c.substring(0, c.lastIndexOf('.')))
                        .anyMatch(owner -> !owner.equals(controller.getName()) && holdsReadScope(owner));
                if ((takesEngine(controller, route) || iterates || scoped)
                        && preAuthorize(controller, route).isEmpty()) {
                    noRole.put(controller.getSimpleName() + "." + route.getName(), "no role expression");
                }
            }
        }
        assertThat(noRole.keySet()).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static boolean holdsReadScope(String owner) {
        return owner.equals(ReadScopeGate.class.getName())
                || (MAIN.contain(owner)
                        && MAIN.get(owner).getAllFields().stream()
                                .anyMatch(f -> f.getRawType().isEquivalentTo(ReadScopeGate.class)));
    }

    private static boolean isRoute(JavaMethod m) {
        return Arrays.stream(((Method) m.reflect()).getAnnotations())
                .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)
                        || a.annotationType().equals(RequestMapping.class));
    }

    private static boolean takesEngine(JavaClass controller, JavaMethod m) {
        Method method = (Method) m.reflect();
        if (Arrays.stream(method.getParameters())
                .map(Parameter::getName)
                .anyMatch(n -> n.equals("engineId") || n.equals("engineIds"))) {
            return true;
        }
        return paths(controller, method).stream().anyMatch(p -> p.matches(".*/engines/\\{[^}]+}.*"));
    }

    private static List<String> paths(JavaClass controller, Method method) {
        String base = controller
                .tryGetAnnotationOfType(RequestMapping.class)
                .map(r -> r.value().length > 0 ? r.value()[0] : "")
                .orElse("");
        return Arrays.stream(method.getAnnotations())
                .map(a -> {
                    try {
                        Object v = a.annotationType().getMethod("value").invoke(a);
                        return v instanceof String[] s && s.length > 0 ? s[0] : "";
                    } catch (ReflectiveOperationException e) {
                        return "";
                    }
                })
                .map(p -> base + p)
                .toList();
    }

    private static String preAuthorize(JavaClass controller, JavaMethod m) {
        PreAuthorize own = ((Method) m.reflect()).getAnnotation(PreAuthorize.class);
        if (own != null) {
            return own.value();
        }
        return controller
                .tryGetAnnotationOfType(PreAuthorize.class)
                .map(PreAuthorize::value)
                .orElse("");
    }

    /** Owner.name of every call the route makes, through its own class's helpers and lambdas. */
    private static Set<String> reachableCalls(JavaClass controller, JavaMethod route) {
        Set<String> out = new HashSet<>();
        Set<JavaCodeUnit> seen = new HashSet<>();
        Deque<JavaCodeUnit> todo = new ArrayDeque<>();
        todo.add(route);
        controller.getMethods().stream()
                .filter(x -> x.getName().startsWith("lambda$" + route.getName() + "$"))
                .forEach(todo::add);
        while (!todo.isEmpty()) {
            JavaCodeUnit unit = todo.pop();
            if (!seen.add(unit)) {
                continue;
            }
            for (JavaMethodCall call : unit.getMethodCallsFromSelf()) {
                out.add(call.getTargetOwner().getName() + "." + call.getName());
                if (call.getTargetOwner().equals(controller)) {
                    call.getTarget().resolveMember().ifPresent(target -> {
                        todo.add(target);
                        controller.getMethods().stream()
                                .filter(x -> x.getName().startsWith("lambda$" + target.getName() + "$"))
                                .forEach(todo::add);
                    });
                }
            }
        }
        return out;
    }
}
