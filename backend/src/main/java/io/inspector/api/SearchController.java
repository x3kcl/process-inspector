package io.inspector.api;

import io.inspector.aggregate.SearchService;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchResponse;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping
    public SearchResponse search(@RequestBody SearchRequest request) {
        SearchResponse result = searchService.search(request);
        // The copy-as-cURL uses the URL this request actually arrived on, so the copied
        // command works from wherever the operator is (SPEC Stage 1).
        String url = ServletUriComponentsBuilder.fromCurrentRequest().toUriString();
        return result.withPresentation(CriteriaEcho.echo(request), CriteriaEcho.curl(url, request));
    }

    /** Bad filter input (unparseable failure window, unknown sortBy) is a 400, not a 500. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
