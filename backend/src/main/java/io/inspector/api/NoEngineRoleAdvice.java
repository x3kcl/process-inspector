package io.inspector.api;

import io.inspector.security.RbacAuthorizer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * #758 (R-SAFE-17): a refusal of a signed-in user who holds NO ladder role on ANY engine carries the
 * named code {@code no-engine-role}, so the SPA can render "signed in, no access granted yet" instead
 * of treating it as a per-engine refusal. Every other refusal is rethrown unchanged (Spring then
 * answers it on the container {@code /error} path exactly as before).
 */
@RestControllerAdvice
public class NoEngineRoleAdvice {

    static final String CODE = "no-engine-role";

    private final RbacAuthorizer rbac;

    public NoEngineRoleAdvice(RbacAuthorizer rbac) {
        this.rbac = rbac;
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail noEngineRole(AccessDeniedException e) {
        if (!rbac.grantsFor(SecurityContextHolder.getContext().getAuthentication())
                .isEmpty()) {
            throw e; // a role holder's refusal: not ours to rename
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN,
                "You are signed in, but no access has been granted yet — ask an Inspector admin.");
        problem.setTitle("No access granted");
        problem.setProperty("code", CODE);
        return problem;
    }
}
