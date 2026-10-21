package io.inspector.registry;

import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.RegistryProperties;
import io.inspector.ops.PrometheusText;
import io.inspector.ops.PrometheusText.Sample;
import io.inspector.registry.RegistryUrlValidator.Rejected;
import io.inspector.registry.RegistryUrlValidator.Result;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Management facts for one engine (#509) plus an optional actuator scrape (#510). A missing route
 * is a labelled failure, never a zero. No metrics URL means executor internals are not exposed.
 */
@Service
public class EngineSystemInfoService {

    private static final int MAX_METRICS_BYTES = 256 * 1024;

    private final EngineRegistry registry;
    private final ProcessApiClient flowable;
    private final RegistryUrlValidator urls;
    private final RegistryProperties registryProperties;
    private final Environment env;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public EngineSystemInfoService(
            EngineRegistry registry,
            ProcessApiClient flowable,
            RegistryUrlValidator urls,
            RegistryProperties registryProperties,
            Environment env) {
        this.registry = registry;
        this.flowable = flowable;
        this.urls = urls;
        this.registryProperties = registryProperties;
        this.env = env;
    }

    public EngineSystemInfo load(String engineId, boolean full) {
        EngineConfig engine = registry.resolveOrNotFound(engineId);
        List<String> failures = new ArrayList<>();
        String version = version(engine, failures);
        if (!full) {
            return new EngineSystemInfo(engineId, version, null, List.of(), failures, null, "version-only", List.of());
        }
        Map<String, Object> properties = map(engine, "/management/properties", failures, "properties");
        List<TableCount> tables = tables(engine, failures);
        ExecutorView executor = executor(engine);
        return new EngineSystemInfo(
                engineId,
                version,
                properties,
                tables,
                failures,
                executor.summary(),
                executor.status(),
                executor.present());
    }

    private String version(EngineConfig engine, List<String> failures) {
        try {
            Map<String, Object> info = flowable.engineInfo(engine, CallPriority.INTERACTIVE);
            return info != null && info.get("version") != null
                    ? info.get("version").toString()
                    : null;
        } catch (RuntimeException e) {
            failures.add("engine");
            return null;
        }
    }

    private Map<String, Object> map(EngineConfig engine, String path, List<String> failures, String label) {
        try {
            return flowable.getMap(engine, CallPriority.INTERACTIVE, path);
        } catch (HttpClientErrorException.NotFound e) {
            failures.add(label);
            return Map.of();
        } catch (RuntimeException e) {
            failures.add(label);
            return Map.of();
        }
    }

    private List<TableCount> tables(EngineConfig engine, List<String> failures) {
        try {
            FlowablePage page = flowable.getPage(engine, CallPriority.INTERACTIVE, "/management/tables");
            List<TableCount> out = new ArrayList<>();
            for (Map<String, Object> row : page == null ? List.<Map<String, Object>>of() : page.dataOrEmpty()) {
                Object name = row.get("name");
                Object count = row.get("count");
                if (name != null) {
                    out.add(new TableCount(name.toString(), count instanceof Number n ? n.longValue() : null));
                }
            }
            return out;
        } catch (RuntimeException e) {
            failures.add("tables");
            return List.of();
        }
    }

    private ExecutorView executor(EngineConfig engine) {
        String url = engine.metricsUrl();
        if (url == null || url.isBlank()) {
            return new ExecutorView("not exposed by this engine", "absent", List.of());
        }
        Result check = urls.validate(url, engine.environment(), registryProperties.egressPolicy());
        if (check instanceof Rejected) {
            return new ExecutorView("metrics URL rejected", "rejected", List.of());
        }
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .GET();
            String auth = basic(engine);
            if (auth != null) {
                request.header("Authorization", auth);
            }
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return new ExecutorView("metrics endpoint unauthorized", "unauthorized", List.of());
            }
            if (response.statusCode() >= 400) {
                return new ExecutorView("metrics endpoint returned " + response.statusCode(), "unreachable", List.of());
            }
            byte[] body = response.body();
            int keep = Math.min(body.length, MAX_METRICS_BYTES);
            List<Sample> samples = PrometheusText.parse(new String(body, 0, keep, StandardCharsets.UTF_8));
            List<String> present = new ArrayList<>();
            Double rejected = PrometheusText.labeled(samples, "flowable_jobs_async_total", "type", "rejected");
            if (rejected != null) {
                present.add("rejected=" + rejected);
            }
            Double active = null;
            for (Sample sample : samples) {
                if ("flowable_task_executor_active".equals(sample.name())) {
                    active = sample.value();
                }
            }
            if (active != null) {
                present.add("activeThreads=" + active);
            }
            if (present.isEmpty()) {
                return new ExecutorView("scrape returned none of the documented executor families", "ok", List.of());
            }
            return new ExecutorView(String.join(", ", present), "ok", present);
        } catch (Exception e) {
            return new ExecutorView("metrics endpoint unreachable", "unreachable", List.of());
        }
    }

    private String basic(EngineConfig engine) {
        if (engine.auth() == null || engine.auth().passwordRef() == null) {
            return null;
        }
        String password = env.getProperty(engine.auth().passwordRef());
        if (password == null || engine.auth().username() == null) {
            return null;
        }
        String token = engine.auth().username() + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    private record ExecutorView(String summary, String status, List<String> present) {}

    public record TableCount(String name, Long count) {}

    public record EngineSystemInfo(
            String engineId,
            String version,
            Map<String, Object> properties,
            List<TableCount> tables,
            List<String> failures,
            String executorInternals,
            String metricsStatus,
            List<String> metricsPresent) {}
}
