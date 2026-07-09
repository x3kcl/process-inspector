package io.inspector.config;

import io.inspector.api.VerbExistenceInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC wiring. Registers the {@link VerbExistenceInterceptor} on the action routes so an unknown
 * verb answers 404 BEFORE {@code @PreAuthorize} evaluates — the pre-auth verb-existence check that
 * lets {@code RbacAuthorizer.canExecute} fail closed while keeping typo→404 (IDP-SECURITY.md §3.10).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new VerbExistenceInterceptor())
                .addPathPatterns("/api/*/*/*/actions/*", "/api/*/*/*/actions/*/curl");
    }
}
