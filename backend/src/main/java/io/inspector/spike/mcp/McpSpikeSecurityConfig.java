package io.inspector.spike.mcp;

import static org.springframework.security.config.Customizer.withDefaults;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * SPIKE #492 S0 — NOT FOR MERGE. A dedicated filter chain for {@code /mcp} (and the RFC 9728
 * metadata path) ahead of the SPA chains:
 *
 * <ul>
 *   <li>{@code oidc} profile: OAuth 2 resource server (bearer JWT, issuer + audience validated by the
 *       Boot {@code spring.security.oauth2.resourceserver.jwt.*} properties), RFC 9728 metadata, and a
 *       401 + {@code WWW-Authenticate: Bearer resource_metadata=…} challenge.
 *   <li>dev profile: HTTP Basic against the ladder users, 401 on no credentials.
 * </ul>
 *
 * Stateless, no CSRF (the chain never reads a session, so nothing is browser-ambient). The JWT is
 * adapted to the principal shape {@link io.inspector.security.RbacAuthorizer} already understands
 * (an {@link OAuth2AuthenticationToken} over an OAuth2User whose attributes are the token claims) so
 * group→grant resolution runs through the unchanged OidcGroupResolver + MappingSource. No
 * authorities are minted: a ROLE_* authority here would be read as a GLOBAL grant by the dev branch
 * of {@code RbacAuthorizer.grantsFor}.
 */
@Configuration
@ConditionalOnProperty(name = "spike.mcp.chain.enabled", havingValue = "true", matchIfMissing = true)
public class McpSpikeSecurityConfig {

    static final String PRM = "/.well-known/oauth-protected-resource";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain mcpChain(
            HttpSecurity http,
            Environment env,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuer)
            throws Exception {
        http.securityMatcher("/mcp", "/mcp/**", PRM, PRM + "/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers(PRM, PRM + "/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated());
        if (env.acceptsProfiles(Profiles.of("oidc"))) {
            var entryPoint = new BearerTokenAuthenticationEntryPoint();
            // Point the challenge at the /mcp-suffixed metadata so `resource` = <base>/mcp, the audience.
            entryPoint.setResourceMetadataParameterResolver(req -> baseUrl(req) + PRM + "/mcp");
            http.oauth2ResourceServer(rs -> rs.authenticationEntryPoint(entryPoint)
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtToOAuth2UserToken()))
                    .protectedResourceMetadata(
                            m -> m.protectedResourceMetadataCustomizer(b -> b.authorizationServer(issuer)
                                    .scope("openid")
                                    // The filter defaults this to true; the Inspector does not bind tokens to mTLS.
                                    .tlsClientCertificateBoundAccessTokens(false))));
            http.exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint));
        } else {
            http.httpBasic(withDefaults())
                    .exceptionHandling(
                            ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        }
        return http.build();
    }

    private static String baseUrl(jakarta.servlet.http.HttpServletRequest req) {
        return org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromContextPath(req)
                .replacePath(null)
                .replaceQuery(null)
                .toUriString();
    }

    /** Jwt → the OAuth2AuthenticationToken shape RbacAuthorizer resolves groups from. No authorities. */
    static final class JwtToOAuth2UserToken implements Converter<Jwt, AbstractAuthenticationToken> {
        @Override
        public AbstractAuthenticationToken convert(Jwt jwt) {
            Map<String, Object> claims = new java.util.LinkedHashMap<>(jwt.getClaims());
            claims.put("iss", String.valueOf(jwt.getClaims().get("iss")));
            String nameKey = claims.containsKey("preferred_username") ? "preferred_username" : "sub";
            var user = new DefaultOAuth2User(List.of(), claims, nameKey);
            return new OAuth2AuthenticationToken(user, List.of(), "mcp-bearer");
        }
    }
}
