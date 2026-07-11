package io.inspector.api;

import io.inspector.dto.EngineDto;
import io.inspector.registry.EngineRegistry;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/engines — the Stage 0 triage health strip. Always a 200 list: the registry
 * health map has an entry for every engine (unknown() at boot, unreachable(...) after
 * failures), so there is no exception path here by construction.
 *
 * <p>W1#4 (theme T6): the list is the DISPLAY surface — it includes non-active engines
 * (lifecycle {@code disabled}/{@code draft}/{@code probe_failed}) so the dashboard renders
 * them greyed-with-reason instead of silently omitting them (R-SEM-17/R-GOV-04). Fan-out
 * and mutation targets keep resolving through the enabled-only registry views.
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
        return registry.allForDisplay().stream()
                .map(e -> EngineDto.from(e, registry.healthOf(e.id())))
                .toList();
    }
}
