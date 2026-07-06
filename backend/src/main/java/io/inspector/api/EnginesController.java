package io.inspector.api;

import io.inspector.dto.EngineDto;
import io.inspector.registry.EngineRegistry;
import io.inspector.registry.EngineRegistry.EngineHealth;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/engines")
public class EnginesController {

    private final EngineRegistry registry;

    public EnginesController(EngineRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<EngineDto> list() {
        return registry.all().stream().map(e -> {
            EngineHealth h = registry.healthOf(e.id());
            return new EngineDto(e.id(), e.name(), e.environment(), e.color(),
                    h.reachable(), h.version(), h.error());
        }).toList();
    }
}
