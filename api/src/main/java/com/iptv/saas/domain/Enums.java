package com.iptv.saas.domain;

public final class Enums {
    private Enums() {
    }

    public enum UserRole {
        SUPER_ADMIN,
        ADMIN,
        BILLING,
        SUPPORT,
        OPS,
        USER
    }

    public enum OrganizationStatus {
        ACTIVE,
        SUSPENDED,
        CANCELLED
    }

    public enum SubscriptionStatus {
        TRIALING,
        ACTIVE,
        PAST_DUE,
        SUSPENDED,
        CANCELLED
    }

    public enum PlanEntitlementMode {
        ALL,
        TYPE,
        CATEGORY,
        KEYWORD,
        ADDON,
        CONNECTOR
    }

    public enum PaymentStatus {
        PENDING,
        VERIFIED,
        REJECTED,
        EXPIRED
    }

    public enum InvoiceStatus {
        DRAFT,
        ISSUED,
        SENT,
        DOWNLOADED
    }

    public enum IptvAccountType {
        XTREAM,
        M3U
    }

    public enum AddonStatus {
        PENDING,
        APPROVED,
        DISABLED
    }

    public enum SessionStatus {
        ACTIVE,
        CLOSED,
        EXPIRED
    }

    public enum TicketStatus {
        OPEN,
        ANSWERED,
        PENDING,
        CLOSED
    }

    public enum TicketPriority {
        LOW,
        NORMAL,
        HIGH,
        URGENT
    }

    public enum UptimeStatus {
        UNKNOWN,
        OK,
        DEGRADED,
        DOWN
    }
}
