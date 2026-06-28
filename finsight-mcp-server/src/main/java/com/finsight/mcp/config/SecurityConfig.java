package com.finsight.mcp.config;

import com.finsight.mcp.security.TenantContextFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.net.URI;
import java.util.List;

/**
 * Security configuration for the FinSight MCP server.
 *
 * - All /mcp/** endpoints require a valid JWT
 * - Actuator health/prometheus endpoints are public
 * - OAuth 2.1 discovery endpoints are public
 * - Token proxy endpoint is public (auth happens at Keycloak)
 * - Stateless — no sessions (JWT only)
 * - TenantContextFilter runs after JWT validation
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${finsight.public-url:https://57af-84-144-71-117.ngrok-free.app}")
    private String publicUrl;

    private final TenantContextFilter tenantContextFilter;

    public SecurityConfig(TenantContextFilter tenantContextFilter) {
        this.tenantContextFilter = tenantContextFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints — infrastructure and OAuth discovery
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus",
                                // OAuth 2.1 discovery endpoints (RFC 9728, RFC 8414)
                                "/.well-known/oauth-protected-resource",
                                "/.well-known/oauth-authorization-server",
                                "/.well-known/openid-configuration",
                                // Token proxy — strips RFC 8707 'resource' parameter
                                // before forwarding to Keycloak (auth happens at Keycloak)
                                "/realms/*/protocol/openid-connect/token"
                        ).permitAll()
                        .requestMatchers("/mcp/**").authenticated()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .protectedResourceMetadata(metadata -> metadata
                                .protectedResourceMetadataCustomizer(prm ->
                                        prm
                                                .resource(URI.create(publicUrl).toString())
                                                .authorizationServers(servers -> {
                                                    servers.clear();
                                                    servers.add(publicUrl);
                                                })
                                                .scopes(scopes -> {
                                                    scopes.clear();
                                                    scopes.addAll(List.of(
                                                            "payment:read", "payment:write",
                                                            "fraud:read", "banking:read"
                                                    ));
                                                })
                                )
                        )
                )
                .addFilterAfter(tenantContextFilter, BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Converts JWT scopes (e.g. "payment:read") into Spring Security authorities
     * so we can use @PreAuthorize("hasAuthority('SCOPE_payment:read')") on tool methods.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter =
                new JwtGrantedAuthoritiesConverter();
        grantedAuthoritiesConverter.setAuthorityPrefix("SCOPE_");
        grantedAuthoritiesConverter.setAuthoritiesClaimName("scope");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return converter;
    }
}