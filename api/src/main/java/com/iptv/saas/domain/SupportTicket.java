package com.iptv.saas.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_tickets")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class SupportTicket extends BaseEntity {
    @ManyToOne(fetch = FetchType.EAGER)
    public Organization organization;

    @ManyToOne(fetch = FetchType.EAGER)
    public UserEntity user;

    @ManyToOne(fetch = FetchType.EAGER)
    public UserEntity assignedTo;

    @Column(nullable = false)
    public String subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Enums.TicketPriority priority = Enums.TicketPriority.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Enums.TicketStatus status = Enums.TicketStatus.OPEN;
}
