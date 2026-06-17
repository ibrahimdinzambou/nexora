package com.iptv.saas.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
public class DocsController {
    @GetMapping("/api/docs")
    public Object docs() {
        return Responses.ok(Map.of(
                "title", "IPTV SaaS API",
                "version", "0.1.0-springboot",
                "auth", Map.of("type", "Bearer token", "header", "Authorization: Bearer {token}"),
                "swagger", Map.of(
                        "ui", "/swagger-ui.html",
                        "openapiJson", "/v3/api-docs",
                        "openapiYaml", "/v3/api-docs.yaml"
                ),
                "groups", List.of(
                        group("Auth", "/api/auth/register", "/api/auth/login", "/api/auth/me", "/api/auth/logout"),
                        group("Billing", "/api/billing/plans", "/api/billing/payments", "/api/invoices"),
                        group("Organizations", "/api/organizations"),
                        group(
                                "Catalog",
                                "/api/catalog/categories",
                                "/api/catalog/items",
                                "/api/catalog/items/{itemId}",
                                "/api/catalog/series/{seriesId}"
                        ),
                        group("Streaming", "/api/stream/open", "/api/stream/url/{sessionToken}", "/api/stream/preflight/{sessionToken}", "/api/stream/quality/{sessionToken}", "/api/stream/heartbeat/{sessionToken}", "/api/stream/close/{sessionToken}"),
                        group("Support", "/api/support/tickets"),
                        group("Admin", "/api/admin/saas/dashboard", "/api/admin/accounts", "/api/admin/billing/payments", "/api/admin/ops/health")
                )
        ));
    }

    @GetMapping("/api/documentation")
    public ResponseEntity<Void> documentation() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, URI.create("/swagger-ui.html").toString())
                .build();
    }

    private Map<String, Object> group(String name, String... endpoints) {
        return Map.of("name", name, "endpoints", List.of(endpoints));
    }
}
