package io.inspector.api;

import io.inspector.dto.TriageDashboardResponse;
import io.inspector.triage.TriageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/triage — the Stage 0 landing (SPEC §4): engine health strip, global status
 * counts and error groups, served from the 20s BFF cache. {@code ?refresh=true} bypasses
 * the cache (rate-limited in {@link TriageService}; a throttled refresh serves the
 * cached snapshot — the {@code asOf} stamp tells the truth either way). Always a 200:
 * engine failures degrade into the response's {@code perEngine} envelopes.
 */
@RestController
@RequestMapping("/api/triage")
public class TriageController {

    private final TriageService triage;

    public TriageController(TriageService triage) {
        this.triage = triage;
    }

    @GetMapping
    public TriageDashboardResponse dashboard(@RequestParam(defaultValue = "false") boolean refresh) {
        return triage.dashboard(refresh);
    }
}
