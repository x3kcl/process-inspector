package guardfixture;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** #791: planted mutants for the role-expression guard's selftest; never component-scanned. */
@RestController
class MutantController {

    @GetMapping("/api/engines/{engineId}/a")
    @PreAuthorize("isAuthenticated()")
    String signedInOnly(@PathVariable String engineId) {
        return engineId;
    }

    @GetMapping("/api/engines/{engineId}/b")
    @PreAuthorize("permitAll")
    String permitAllOnly(@PathVariable String engineId) {
        return engineId;
    }

    @GetMapping("/api/engines/{engineId}/c")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId) or isAuthenticated()")
    String roleOrSignedIn(@PathVariable String engineId) {
        return engineId;
    }

    @GetMapping("/api/engines/{engineId}/ok")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    String gated(@PathVariable String engineId) {
        return engineId;
    }
}
