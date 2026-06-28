package com.finsight.mcp.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP OAuth 2.1 discovery endpoints.
 *
 * Tells Claude Desktop where to find the authorization server (Keycloak)
 * and what scopes are available.
 *
 * OAuth 2.1 flow:
 *   1. Claude calls /mcp → gets 401
 *   2. Claude reads /.well-known/oauth-protected-resource → finds Keycloak
 *   3. Claude reads /.well-known/oauth-authorization-server → gets endpoints
 *   4. Claude opens browser → user logs in via Keycloak
 *   5. Claude calls our /realms/{realm}/protocol/openid-connect/token proxy
 *      which strips the RFC 8707 'resource' parameter Keycloak 25 doesn't support
 *   6. Claude calls /mcp with Bearer token
 *
 * Note: Keycloak is configured with --hostname=<ngrok-url> so it returns
 * correct public URLs in its discovery document — no URL rewriting needed.
 */
@RestController
@Slf4j
public class OAuthDiscoveryController {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${finsight.public-url:https://57af-84-144-71-117.ngrok-free.app}")
    private String publicUrl;

    private final RestClient restClient = RestClient.create();

    /**
     * RFC 9728 — Protected Resource Metadata.
     *
     * Tells Claude which authorization server protects this MCP server.
     * Claude reads this after receiving a 401 from /mcp.
     */
    @GetMapping("/.well-known/oauth-protected-resource")
    public ResponseEntity<Map<String, Object>> protectedResourceMetadata() {
        return ResponseEntity.ok(Map.of(
                "resource", publicUrl,
                "authorization_servers", List.of(issuerUri),
                "scopes_supported", List.of(
                        "payment:read", "payment:write",
                        "fraud:read", "banking:read"
                ),
                "bearer_methods_supported", List.of("header")
        ));
    }

    /**
     * RFC 8414 — Authorization Server Metadata.
     *
     * Proxies Keycloak's OpenID Connect discovery document.
     * Keycloak already returns correct ngrok URLs via --hostname flag.
     * We only override token_endpoint to point to our proxy which strips
     * the RFC 8707 'resource' parameter that Keycloak 25 doesn't support.
     */
    @GetMapping("/.well-known/oauth-authorization-server")
    public ResponseEntity<Map<String, Object>> authorizationServerMetadata() {
        String keycloakDiscovery = issuerUri + "/.well-known/openid-configuration";
        Map<String, Object> metadata = restClient.get()
                .uri(keycloakDiscovery)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        Map<String, Object> result = new HashMap<>(metadata);

        // Override token_endpoint to our proxy which strips 'resource' parameter
        result.put("token_endpoint",
                publicUrl + "/realms/finsight/protocol/openid-connect/token");

        return ResponseEntity.ok(result);
    }

    /**
     * Alias for /.well-known/oauth-authorization-server.
     * Claude Desktop may try this path when discovering the authorization server.
     */
    @GetMapping("/.well-known/openid-configuration")
    public ResponseEntity<Map<String, Object>> openIdConfiguration() {
        return authorizationServerMetadata();
    }

    /**
     * Token proxy endpoint.
     *
     * Claude Desktop sends the RFC 8707 'resource' parameter in token requests
     * but Keycloak 25 doesn't support it and returns 'not_allowed'.
     * This proxy strips 'resource' and 'offline_access' before forwarding to Keycloak.
     */
    @PostMapping("/realms/{realm}/protocol/openid-connect/token")
    public ResponseEntity<Map<String, Object>> proxyKeycloakToken(
            @PathVariable String realm,
            @RequestParam MultiValueMap<String, String> params,
            HttpServletRequest request) {

        String cleanBody = params.entrySet().stream()
                .filter(e -> !e.getKey().equals("resource"))
                .flatMap(e -> e.getValue().stream()
                        .map(v -> {
                            try {
                                return e.getKey() + "=" +
                                        java.net.URLEncoder.encode(v,
                                                java.nio.charset.StandardCharsets.UTF_8);
                            } catch (Exception ex) {
                                return e.getKey() + "=" + v;
                            }
                        }))
                .collect(Collectors.joining("&"));

        log.debug("[PROXY] Forwarding token request to Keycloak, realm={}", realm);

        String tokenEndpoint = issuerUri + "/protocol/openid-connect/token";
        try {
            Map<String, Object> response = restClient.post()
                    .uri(tokenEndpoint)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body(cleanBody)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            log.debug("[PROXY] Token exchange successful");
            return ResponseEntity.ok(response);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.debug("[PROXY] Keycloak error status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            try {
                Map<String, Object> errorBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(e.getResponseBodyAsString(),
                                new com.fasterxml.jackson.core.type.TypeReference<>() {});
                return ResponseEntity.status(e.getStatusCode()).body(errorBody);
            } catch (Exception ex) {
                return ResponseEntity.status(e.getStatusCode())
                        .body(Map.of("error", "proxy_error",
                                "error_description", e.getMessage()));
            }
        }
    }
}