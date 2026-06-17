package com.iptv.saas.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_logs")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class AuditLog extends BaseEntity {
    @ManyToOne(fetch = FetchType.EAGER)
    public UserEntity user;

    @Column(nullable = false)
    public String action;

    public String subjectType;
    public Long subjectId;
    public String ipAddress;

    @Column(length = 12000)
    public String metadata;
}
