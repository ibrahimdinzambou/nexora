package com.iptv.saas.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_messages")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class SupportMessage extends BaseEntity {
    @ManyToOne(fetch = FetchType.EAGER)
    public SupportTicket ticket;

    @ManyToOne(fetch = FetchType.EAGER)
    public UserEntity author;

    @Column(nullable = false, length = 10000)
    public String body;

    public boolean internalMessage = false;
}
