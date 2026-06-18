package com.iptv.saas.web;

import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.service.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public Object inbox() {
        var inbox = notifications.inbox(SecurityUtils.currentUser());
        var body = Responses.map();
        body.put("items", inbox.items().stream().map(ApiMappers::notification).toList());
        body.put("unreadCount", inbox.unreadCount());
        return Responses.ok(body);
    }

    @PostMapping("/{id}/read")
    public Object markRead(@PathVariable Long id) {
        return Responses.ok(ApiMappers.notification(notifications.markRead(SecurityUtils.currentUser(), id)));
    }

    @PostMapping("/read-all")
    public Object markAllRead() {
        return Responses.ok(java.util.Map.of("updated", notifications.markAllRead(SecurityUtils.currentUser())));
    }
}
