package com.iptv.saas.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "user_notifications", indexes = {
        @Index(name = "idx_user_notifications_user", columnList = "user_id"),
        @Index(name = "idx_user_notifications_read", columnList = "user_id,read_at"),
        @Index(name = "idx_user_notifications_published", columnList = "published_at")
})
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class UserNotification extends BaseEntity {
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    public UserEntity user;

    @Column(nullable = false, length = 40)
    public String type = "message";

    @Column(nullable = false, length = 160)
    public String title;

    @Column(nullable = false, length = 2000)
    public String body;

    @Column(length = 1000)
    public String targetUrl;

    @Column(name = "published_at", nullable = false)
    public Instant publishedAt = Instant.now();

    @Column(name = "read_at")
    public Instant readAt;
}
