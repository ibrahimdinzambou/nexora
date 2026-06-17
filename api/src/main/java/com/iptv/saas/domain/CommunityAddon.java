package com.iptv.saas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinTable;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "community_addons", indexes = {
        @Index(name = "idx_community_addons_manifest_url", columnList = "manifest_url", unique = true)
})
public class CommunityAddon extends BaseEntity {
    @Column(name = "manifest_url", nullable = false, unique = true, length = 2000)
    public String manifestUrl;

    @Column(nullable = false)
    public String addonKey;

    @Column(nullable = false)
    public String name;

    public String version;

    @Column(length = 2000)
    public String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Enums.AddonStatus status = Enums.AddonStatus.PENDING;

    @Column(length = 4000)
    public String allowedStreamHosts;

    public String licenseName;

    @Column(length = 2000)
    public String licenseUrl;

    public boolean adultContent;

    @Column(nullable = false)
    public boolean privateUse;

    @ManyToOne(fetch = FetchType.EAGER)
    public UserEntity owner;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "community_addon_allowed_users",
            joinColumns = @JoinColumn(name = "addon_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    public Set<UserEntity> allowedUsers = new LinkedHashSet<>();

    @Lob
    public String manifestJson;

    public Instant lastCheckedAt;

    @Column(length = 2000)
    public String lastError;
}
