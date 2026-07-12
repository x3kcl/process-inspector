package io.inspector.api;

import io.inspector.aggregate.SearchService;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchResponse;
import io.inspector.security.ReadScopeGate;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;
    private final ReadScopeGate readScope;

    public SearchController(SearchService searchService, ReadScopeGate readScope) {
        this.searchService = searchService;
        this.readScope = readScope;
    }

    @PostMapping
    public SearchResponse search(@RequestBody SearchRequest request, Authentication auth) {
        // The copy-as-cURL uses the URL this request actually arrived on, so the copied
        // command works from wherever the operator is (SPEC Stage 1).
        String url = ServletUriComponentsBuilder.fromCurrentRequest().toUriString();
        // S2 (R-SAFE-17): the engines this caller may READ; null = unrestricted (enforcement off).
        // Resolved on the request thread, never inside the fan-out, so the caller's session is authoritative.
        Set<String> readable = readScope.readableEngineIds(auth);
        SearchResponse result =
                notBlank(request.cursor()) ? deepPage(request, readable) : searchService.search(request, readable);
        return result.withPresentation(CriteriaEcho.echo(request), CriteriaEcho.curl(url, request));
    }

    /**
     * "Load more" (v2 deep paging, docs/KWAY-PAGING.md): the SAME endpoint with a cursor present.
     * The filter is re-sent in the body and stays authoritative; the cursor only carries the resume
     * offsets. A deep-paged set is a SNAPSHOT (pagingCoherence) — the frontend shows the "loaded more
     * as of …" seam and Refresh resets the chain. A crafted/expired/mismatched cursor throws
     * IllegalArgumentException → 400 (global {@link ActionExceptionHandler}), never a 500.
     */
    private SearchResponse deepPage(SearchRequest request, Set<String> readable) {
        SearchService.DeepPage page =
                searchService.deepPage(request, request.cursor(), System.currentTimeMillis(), readable);
        return new SearchResponse(
                page.rows(), page.perEngine(), Map.of(), null, null, page.nextCursor(), page.depthCapped(), "snapshot");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
