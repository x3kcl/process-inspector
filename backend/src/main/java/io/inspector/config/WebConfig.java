package io.inspector.config;

import io.inspector.api.RequestIdFilter;
import io.inspector.api.VerbExistenceInterceptor;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
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

    /**
     * R-AUD-04 (usability W1#6): the request-id filter runs at highest precedence — AHEAD of the
     * security chain, so the pre-handler 401/403 answers already carry the id — and on the ERROR
     * dispatch too, so the {@code /error} rendering (bare 403/404 shape) regains MDC context.
     */
    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return registration;
    }
}
