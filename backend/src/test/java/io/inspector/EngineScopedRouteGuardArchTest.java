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
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * #747 (R-SAFE-17): every controller route that reads per-engine data carries an accepted read-scope
 * mechanism, so a new route cannot silently reopen the gaps the #747 sweep closed. A route is
 * ENGINE-SCOPED when it takes an {@code engineId} parameter, names an engine in its path
 * ({@code /engines/{…}}), or iterates the registry ({@code EngineRegistry.all()/allForDisplay()},
 * followed through the controller's own private helpers and lambdas). Accepted mechanisms:
 *
 * <ul>
 *   <li>method security naming the engine: a {@code @PreAuthorize} (method or class) that passes
 *       {@code #engineId} ({@code atLeastOn}, {@code canExecute});
 *   <li>the registry-admin door: {@code @PreAuthorize} with {@code canAdministerRegistry} (fleet-wide
 *       by design — the registry CRUD surface);
 *   <li>the read-scope gate: the route reaches {@code ReadScopeGate.readableEngineIds} or
 *       {@code ReadScopeGate.canRead}.
 * </ul>
 *
 * Anything else is listed in {@link #EXCEPTIONS} by name, with the reason.
 */
class EngineScopedRouteGuardArchTest {

    /** {@code Controller.method} → why it is engine-scoped without one of the mechanisms. */
    static final Map<String, String> EXCEPTIONS = Map.of(
            "MeController.me",
            "lists the caller's OWN role per engine (rbac.hasRoleOn per engine) — an engine with no role is"
                    + " omitted, so it carries nothing the caller cannot already read");

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.inspector");

    @Test
    void everyEngineScopedRouteCarriesAnAcceptedScopeMechanism() {
        Map<String, String> violations = new TreeMap<>();
        Set<String> engineScoped = new TreeSet<>();
        for (JavaClass controller : MAIN) {
            if (!controller.isAnnotatedWith(RestController.class)) {
                continue;
            }
            for (JavaMethod route : controller.getMethods()) {
                if (!isRoute(route)) {
                    continue;
                }
                String name = controller.getSimpleName() + "." + route.getName();
                Set<String> calls = reachableCalls(controller, route);
                boolean iterates = calls.contains(EngineRegistry.class.getName() + ".all")
                        || calls.contains(EngineRegistry.class.getName() + ".allForDisplay");
                if (!(takesEngine(controller, route) || iterates)) {
                    continue;
                }
                engineScoped.add(name);
                String pre = preAuthorize(controller, route);
                boolean scoped = pre.contains("#engineId")
                        || pre.contains("canAdministerRegistry")
                        || calls.contains(ReadScopeGate.class.getName() + ".readableEngineIds")
                        || calls.contains(ReadScopeGate.class.getName() + ".canRead");
                if (!scoped && !EXCEPTIONS.containsKey(name)) {
                    violations.put(name, "no #engineId method security, no ReadScopeGate, not an exception");
                }
            }
        }

        // non-vacuous: the detector sees the path-named, the gated and the iterating kinds
        assertThat(engineScoped)
                .contains(
                        "InstanceDetailController.vitals",
                        "EnginesController.system",
                        "EnginesController.list",
                        "DeploymentsController.list",
                        "JobsExplorerController.list",
                        "TaskBrowseController.identityLinks");
        assertThat(engineScoped).containsAll(EXCEPTIONS.keySet()); // no stale exception
        assertThat(violations).isEmpty();
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
