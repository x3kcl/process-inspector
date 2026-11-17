package io.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.action.ActionRequest;
import io.inspector.client.HistoricVariableRecord;
import io.inspector.client.StartProcessInstanceRequest;
import io.inspector.client.VariableRecord;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * #742 guard: a variable VALUE leaves the BFF only inside {@code InstanceVariables.VariableDto},
 * whose constructor masks secret-named variables. This walks every {@code @RestController}
 * handler's return type (generics and record components, transitively) and fails if any
 * client-side carrier of a raw variable value is reachable — a new surface that hands an engine
 * envelope straight to the browser cannot land without being rerouted through the choke point.
 */
class VariableValueSurfaceArchTest {

    private static final Set<Class<?>> RAW_VALUE_CARRIERS = Set.of(
            VariableRecord.class,
            HistoricVariableRecord.class,
            StartProcessInstanceRequest.Variable.class,
            ActionRequest.TypedVariable.class,
            ActionRequest.VariableEdit.class);

    @Test
    void noControllerReturnsARawVariableValueCarrier() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> offenders = new ArrayList<>();
        int handlers = 0;
        for (var candidate : scanner.findCandidateComponents("io.inspector")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            for (Method method : controller.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) continue;
                handlers++;
                Set<Class<?>> reached = new HashSet<>();
                walk(method.getGenericReturnType(), reached);
                reached.retainAll(RAW_VALUE_CARRIERS);
                if (!reached.isEmpty()) {
                    offenders.add(controller.getSimpleName() + "#" + method.getName() + " → " + reached);
                }
            }
        }
        assertThat(handlers).as("handlers scanned").isGreaterThan(50);
        assertThat(offenders).as("handlers exposing a raw variable value").isEmpty();
    }

    private static void walk(Type type, Set<Class<?>> seen) {
        if (type instanceof ParameterizedType p) {
            walk(p.getRawType(), seen);
            for (Type arg : p.getActualTypeArguments()) walk(arg, seen);
        } else if (type instanceof GenericArrayType a) {
            walk(a.getGenericComponentType(), seen);
        } else if (type instanceof WildcardType w) {
            for (Type bound : w.getUpperBounds()) walk(bound, seen);
        } else if (type instanceof Class<?> c) {
            if (c.isArray()) {
                walk(c.getComponentType(), seen);
                return;
            }
            if (c.isPrimitive() || c.getName().startsWith("java.") || !seen.add(c)) return;
            if (c.isRecord()) {
                for (RecordComponent component : c.getRecordComponents()) walk(component.getGenericType(), seen);
            }
        }
    }
}
