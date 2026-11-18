package guardslipfixture;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import org.springframework.security.access.prepost.PreAuthorize;

/** #791 review: a meta-annotation wrapping an open {@code @PreAuthorize}; Spring honours it. */
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("permitAll")
@interface OpenToAll {}
