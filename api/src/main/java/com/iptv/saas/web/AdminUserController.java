package com.iptv.saas.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iptv.saas.domain.Enums;
import com.iptv.saas.repository.UserRepository;
import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.service.AdminUserService;
import com.iptv.saas.service.AuditService;
import com.iptv.saas.service.NotificationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final AdminUserService adminUsers;
    private final NotificationService notifications;
    private final AuditService audit;

    public AdminUserController(
            UserRepository users,
            ObjectMapper mapper,
            AdminUserService adminUsers,
            NotificationService notifications,
            AuditService audit
    ) {
        this.users = users;
        this.mapper = mapper;
        this.adminUsers = adminUsers;
        this.notifications = notifications;
        this.audit = audit;
    }

    @GetMapping
    public Object users() {
        return Responses.ok(this.users.findAll().stream().map(ApiMappers::user).toList());
    }

    @PatchMapping("/{id}/toggle")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public Object toggle(@PathVariable Long id, @RequestBody(required = false) ToggleRequest request) {
        return Responses.ok(ApiMappers.user(adminUsers.setActive(
                SecurityUtils.currentUser(),
                id,
                request == null ? null : request.active()
        )));
    }

    @PatchMapping("/{id}/role")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public Object role(@PathVariable Long id, @RequestBody RoleRequest request) {
        return Responses.ok(ApiMappers.user(adminUsers.setRole(SecurityUtils.currentUser(), id, request.role())));
    }

    @PostMapping("/{id}/categories")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public Object categories(@PathVariable Long id, @RequestBody CategoriesRequest request) throws Exception {
        var user = users.findById(id).orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
        user.allowedCategories = mapper.writeValueAsString(request.categories());
        return Responses.ok(ApiMappers.user(users.save(user)));
    }

    @PostMapping("/{id}/message")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','SUPPORT')")
    public Object message(@PathVariable Long id, @RequestBody MessageRequest request) {
        var actor = SecurityUtils.currentUser();
        var user = users.findById(id).orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
        var notification = notifications.notifyUser(
                user,
                request.title(),
                request.body(),
                "message",
                request.targetUrl()
        );
        audit.log(actor, "admin.user.message", "UserNotification", notification.id, user.email);
        return Responses.ok(ApiMappers.notification(notification));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public Object delete(@PathVariable Long id) {
        return Responses.ok(ApiMappers.user(adminUsers.deleteUser(SecurityUtils.currentUser(), id)));
    }

    public record ToggleRequest(Boolean active) {
    }

    public record RoleRequest(Enums.UserRole role) {
    }

    public record CategoriesRequest(List<String> categories) {
    }

    public record MessageRequest(String title, String body, String targetUrl) {
    }
}
