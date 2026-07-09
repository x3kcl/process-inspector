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
        // The copy-as-cURL uses the URL this request actually arrived on, so the copied
        // command works from wherever the operator is (SPEC Stage 1).
        String url = ServletUriComponentsBuilder.fromCurrentRequest().toUriString();
        SearchResponse result = notBlank(request.cursor()) ? deepPage(request) : searchService.search(request);
        return result.withPresentation(CriteriaEcho.echo(request), CriteriaEcho.curl(url, request));
    }

    /**
     * "Load more" (v2 deep paging, docs/KWAY-PAGING.md): the SAME endpoint with a cursor present.
     * The filter is re-sent in the body and stays authoritative; the cursor only carries the resume
     * offsets. A deep-paged set is a SNAPSHOT (pagingCoherence) — the frontend shows the "loaded more
     * as of …" seam and Refresh resets the chain. A crafted/expired/mismatched cursor throws
     * IllegalArgumentException → 400 (below), never a 500.
     */
    private SearchResponse deepPage(SearchRequest request) {
        SearchService.DeepPage page = searchService.deepPage(request, request.cursor(), System.currentTimeMillis());
        return new SearchResponse(
                page.rows(), page.perEngine(), Map.of(), null, null, page.nextCursor(), page.depthCapped(), "snapshot");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Bad filter input (unparseable failure window, unknown sortBy) is a 400, not a 500. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
