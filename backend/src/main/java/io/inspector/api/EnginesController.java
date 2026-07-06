package io.inspector.api;

import io.inspector.dto.EngineDto;
import io.inspector.registry.EngineHealth;
import io.inspector.registry.EngineRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/engines — the Stage 0 triage health strip. Always a 200 list: the registry
 * health map has an entry for every engine (unknown() at boot, unreachable(...) after
 * failures), so there is no exception path here by construction.
 */
@RestController
@RequestMapping("/api/engines")
public class EnginesController {

    private final EngineRegistry registry;

    public EnginesController(EngineRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<EngineDto> list() {
        return registry.all().stream()
                .map(e -> {
                    EngineHealth h = registry.healthOf(e.id());
                    return new EngineDto(
                            e.id(),
                            e.name(),
                            e.environment().name().toLowerCase(Locale.ROOT),
                            e.accentColor(),
                            e.modeOrDefault().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                            e.tenantId(),
                            h.reachable(),
                            h.version(),
                            h.checkedAtEpochMs() > 0
                                    ? Instant.ofEpochMilli(h.checkedAtEpochMs()).toString()
                                    : null,
                            h.capabilities(),
                            h.jobLanes(),
                            h.oldestExecutableJobAgeSec(),
                            h.overdueTimers(),
                            h.error());
                })
                .toList();
    }
}
