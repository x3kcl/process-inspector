package io.inspector.api;

import io.inspector.dto.NearestSiblingResponse;
import io.inspector.dto.SiblingDiffResponse;
import io.inspector.sibling.SiblingDiffService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sibling diff resource (SPEC §5.2) under the Stage 2 composite path. Read-only, VIEWER floor
 * per engine — the same read gate as the rest of {@code /api/instances/*}. Both endpoints are
 * historic-only: a completed sibling and both runs' variables/activities come exclusively from
 * the history tables, so nothing here reads (or can mutate) engine runtime state.
 */
@RestController
@RequestMapping("/api/instances/{engineId}/{instanceId}")
public class SiblingDiffController {

    private final SiblingDiffService siblings;

    public SiblingDiffController(SiblingDiffService siblings) {
        this.siblings = siblings;
    }

    /** The smart default: the most recently completed instance of the same definition version. */
    @GetMapping("/nearest-sibling")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public NearestSiblingResponse nearestSibling(@PathVariable String engineId, @PathVariable String instanceId) {
        return siblings.nearestSibling(engineId, instanceId);
    }

    /** The three-way comparison of this instance (subject) against a chosen sibling. */
    @GetMapping("/diff/{siblingId}")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public SiblingDiffResponse diff(
            @PathVariable String engineId, @PathVariable String instanceId, @PathVariable String siblingId) {
        return siblings.diff(engineId, instanceId, siblingId);
    }
}
