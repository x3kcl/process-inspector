package guardslipfixture;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** #791 review: an open CLASS-level expression the route inherits. */
@RestController
@PreAuthorize("permitAll")
class OpenClassController {

    @GetMapping("/api/engines/{engineId}/open-class")
    String inheritsOpen(@PathVariable String engineId) {
        return engineId;
    }
}
