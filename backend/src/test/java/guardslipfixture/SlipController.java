package guardslipfixture;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** #791 review: expressions that name an RBAC gate yet admit a caller without one; never component-scanned. */
@RestController
@PreAuthorize("@rbac.atLeast(authentication, 'VIEWER')")
class SlipController {

    @GetMapping("/api/engines/{engineId}/and-true")
    @PreAuthorize("isAuthenticated() and true")
    String andTrue(@PathVariable String engineId) {
        return engineId;
    }

    @GetMapping("/api/engines/{engineId}/no-such-role")
    @PreAuthorize("hasRole('NOT_IN_LADDER')")
    String undefinedRole(@PathVariable String engineId) {
        return engineId;
    }

    @GetMapping("/api/engines/{engineId}/or-true")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId) or true")
    String orTrue(@PathVariable String engineId) {
        return engineId;
    }

    @GetMapping("/api/engines/{engineId}/or-has-role")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId) or hasRole('USER')")
    String orHasRole(@PathVariable String engineId) {
        return engineId;
    }

    @GetMapping("/api/engines/{engineId}/negated")
    @PreAuthorize("!@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    String negated(@PathVariable String engineId) {
        return engineId;
    }

    @GetMapping("/api/engines/{engineId}/literal")
    @PreAuthorize("'@rbac.atLeast(' != null")
    String inLiteral(@PathVariable String engineId) {
        return engineId;
    }

    @GetMapping("/api/engines/{engineId}/meta-open")
    @OpenToAll
    String metaOpen(@PathVariable String engineId) {
        return engineId;
    }

    // Controls: accepted. A fleet floor on an engine route passes THIS guard; naming the engine is
    // EngineScopedRouteGuardArchTest's job (#engineId or ReadScopeGate).
    @GetMapping("/api/engines/{engineId}/fleet-floor")
    @PreAuthorize("@rbac.atLeast(authentication, 'VIEWER')")
    String fleetFloor(@PathVariable String engineId) {
        return engineId;
    }

    @GetMapping("/api/engines/{engineId}/two-gates")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId) and @rbac.atLeast(authentication, 'OPERATOR')")
    String twoGates(@PathVariable String engineId) {
        return engineId;
    }

    @GetMapping("/api/engines/{engineId}/either-gate")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'ADMIN', #engineId) or @rbac.canAdministerRegistry(authentication)")
    String eitherGate(@PathVariable String engineId) {
        return engineId;
    }

    @GetMapping("/api/engines/{engineId}/inherited")
    String inherited(@PathVariable String engineId) {
        return engineId;
    }
}
