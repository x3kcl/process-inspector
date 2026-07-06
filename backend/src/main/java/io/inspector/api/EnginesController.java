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
                .map(e -> EngineDto.from(e, registry.healthOf(e.id())))
                .toList();
    }
}
