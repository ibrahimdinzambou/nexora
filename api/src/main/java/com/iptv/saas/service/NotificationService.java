package com.iptv.saas.service;

import com.iptv.saas.domain.AppState;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.domain.UserNotification;
import com.iptv.saas.repository.AppStateRepository;
import com.iptv.saas.repository.UserNotificationRepository;
import com.iptv.saas.repository.UserRepository;
import com.iptv.saas.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class NotificationService {
    private static final String CATALOG_TOTAL_KEY = "catalog.totalContent";

    private final UserNotificationRepository notifications;
    private final AppStateRepository states;
    private final UserRepository users;

    public NotificationService(
            UserNotificationRepository notifications,
            AppStateRepository states,
            UserRepository users
    ) {
        this.notifications = notifications;
        this.states = states;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Inbox inbox(UserEntity user) {
        return new Inbox(
                notifications.findTop20ByUserOrderByPublishedAtDescIdDesc(user),
                notifications.countByUserAndReadAtIsNull(user)
        );
    }

    @Transactional
    public UserNotification notifyUser(UserEntity user, String title, String body, String type, String targetUrl) {
        if (user == null || !user.active) {
            throw ApiException.notFound("Utilisateur introuvable ou inactif");
        }
        UserNotification notification = new UserNotification();
        notification.user = user;
        notification.title = clean(title, "Notification Nexora", 160);
        notification.body = clean(body, "Vous avez un nouveau message.", 2000);
        notification.type = clean(type, "message", 40);
        notification.targetUrl = blankToNull(targetUrl, 1000);
        notification.publishedAt = Instant.now();
        return notifications.save(notification);
    }

    @Transactional
    public void notifyActiveUsers(String title, String body, String type, String targetUrl) {
        for (UserEntity user : users.findByActive(true)) {
            notifyUser(user, title, body, type, targetUrl);
        }
    }

    @Transactional
    public void notifyCatalogGrowth(int totalContent) {
        AppState state = states.findByStateKey(CATALOG_TOTAL_KEY).orElseGet(() -> {
            AppState created = new AppState();
            created.stateKey = CATALOG_TOTAL_KEY;
            created.stateValue = "0";
            return created;
        });
        int previous = parseInt(state.stateValue);
        if (previous > 0 && totalContent > previous) {
            int added = totalContent - previous;
            notifyActiveUsers(
                    "Nouveaux contenus disponibles",
                    added + " nouveau(x) contenu(s) ont ete detectes dans le catalogue.",
                    "catalog",
                    "/watch.html"
            );
        }
        state.stateValue = String.valueOf(Math.max(totalContent, previous));
        states.save(state);
    }

    @Transactional
    public UserNotification markRead(UserEntity user, Long id) {
        UserNotification notification = notifications.findByUserAndId(user, id)
                .orElseThrow(() -> ApiException.notFound("Notification introuvable"));
        if (notification.readAt == null) {
            notification.readAt = Instant.now();
        }
        return notifications.save(notification);
    }

    @Transactional
    public int markAllRead(UserEntity user) {
        List<UserNotification> unread = notifications.findByUserAndReadAtIsNull(user);
        Instant now = Instant.now();
        unread.forEach(notification -> notification.readAt = now);
        notifications.saveAll(unread);
        return unread.size();
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private String clean(String value, String fallback, int maxLength) {
        String cleaned = value == null || value.isBlank() ? fallback : value.strip();
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    private String blankToNull(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value.strip();
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    public record Inbox(List<UserNotification> items, long unreadCount) {
    }
}
