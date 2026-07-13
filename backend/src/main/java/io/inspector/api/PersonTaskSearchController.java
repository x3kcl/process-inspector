package io.inspector.api;

import io.inspector.aggregate.PersonTaskSearchService;
import io.inspector.dto.PersonTaskSearchResponse;
import io.inspector.security.ReadScopeGate;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/tasks?person=&engineIds= — person-centric task search (issue #99): "Bob is on
 * vacation, what is he sitting on?" across every readable engine. VIEWER-floor read (no
 * {@code @PreAuthorize}), scope-filtered exactly like {@link SearchController} — rows feed the
 * EXISTING reassign/return-to-team verbs unchanged, which keep their own OPERATOR gate.
 */
@RestController
@RequestMapping("/api/tasks")
public class PersonTaskSearchController {

    private final PersonTaskSearchService taskSearch;
    private final ReadScopeGate readScope;

    public PersonTaskSearchController(PersonTaskSearchService taskSearch, ReadScopeGate readScope) {
        this.taskSearch = taskSearch;
        this.readScope = readScope;
    }

    @GetMapping
    public PersonTaskSearchResponse searchByPerson(
            @RequestParam String person, @RequestParam(required = false) Set<String> engineIds, Authentication auth) {
        if (person.isBlank()) {
            throw new IllegalArgumentException("person is required");
        }
        // S2 (R-SAFE-17): resolved on the request thread, never inside the fan-out.
        Set<String> readable = readScope.readableEngineIds(auth);
        return taskSearch.search(person.strip(), engineIds, readable);
    }
}
