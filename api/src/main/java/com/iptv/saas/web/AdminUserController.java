package com.iptv.saas.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iptv.saas.domain.Enums;
import com.iptv.saas.repository.UserRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {
    private final UserRepository users;
    private final ObjectMapper mapper;

    public AdminUserController(UserRepository users, ObjectMapper mapper) {
        this.users = users;
        this.mapper = mapper;
    }

    @GetMapping
    public Object users() {
        return Responses.ok(this.users.findAll().stream().map(ApiMappers::user).toList());
    }

    @PatchMapping("/{id}/toggle")
    public Object toggle(@PathVariable Long id, @RequestBody(required = false) ToggleRequest request) {
        var user = users.findById(id).orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
        user.active = request == null || request.active() == null ? !user.active : request.active();
        return Responses.ok(ApiMappers.user(users.save(user)));
    }

    @PatchMapping("/{id}/role")
    public Object role(@PathVariable Long id, @RequestBody RoleRequest request) {
        var user = users.findById(id).orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
        user.role = request.role();
        return Responses.ok(ApiMappers.user(users.save(user)));
    }

    @PostMapping("/{id}/categories")
    public Object categories(@PathVariable Long id, @RequestBody CategoriesRequest request) throws Exception {
        var user = users.findById(id).orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
        user.allowedCategories = mapper.writeValueAsString(request.categories());
        return Responses.ok(ApiMappers.user(users.save(user)));
    }

    public record ToggleRequest(Boolean active) {
    }

    public record RoleRequest(Enums.UserRole role) {
    }

    public record CategoriesRequest(List<String> categories) {
    }
}
