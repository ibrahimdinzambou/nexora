package com.iptv.saas.web;

import com.iptv.saas.domain.*;
import com.iptv.saas.security.CatalogCategoryAccess;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ApiMappers {
    private ApiMappers() {
    }

    public static Map<String, Object> user(UserEntity user) {
        if (user == null) {
            return null;
        }
        Map<String, Object> m = map();
        m.put("id", user.id);
        m.put("name", user.name);
        m.put("email", user.email);
        m.put("role", user.role);
        m.put("active", user.active);
        m.put("emailVerified", user.emailVerified);
        m.put("twoFactorEnabled", user.twoFactorEnabled);
        m.put("currentOrganizationId", user.currentOrganization == null ? null : user.currentOrganization.id);
        m.put("allowedCategories", CatalogCategoryAccess.allowedCategoryIds(user));
        m.put("createdAt", user.createdAt);
        return m;
    }

    public static Map<String, Object> organization(Organization organization) {
        if (organization == null) {
            return null;
        }
        Map<String, Object> m = map();
        m.put("id", organization.id);
        m.put("name", organization.name);
        m.put("slug", organization.slug);
        m.put("status", organization.status);
        m.put("billingEmail", organization.billingEmail);
        m.put("ownerId", organization.owner == null ? null : organization.owner.id);
        m.put("createdAt", organization.createdAt);
        return m;
    }

    public static Map<String, Object> membership(OrganizationMembership membership) {
        Map<String, Object> m = map();
        m.put("id", membership.id);
        m.put("organizationId", membership.organization == null ? null : membership.organization.id);
        m.put("user", user(membership.user));
        m.put("role", membership.role);
        m.put("status", membership.status);
        return m;
    }

    public static Map<String, Object> plan(Plan plan) {
        Map<String, Object> m = map();
        m.put("id", plan.id);
        m.put("code", plan.code);
        m.put("name", plan.name);
        m.put("priceMonthly", plan.priceMonthly);
        m.put("currency", plan.currency);
        m.put("description", plan.description);
        m.put("highlight", plan.highlight);
        m.put("trialDays", plan.trialDays);
        m.put("billingPeriodDays", plan.billingPeriodDays == null ? 30 : plan.billingPeriodDays);
        m.put("maxUsers", plan.maxUsers);
        m.put("maxIptvAccounts", plan.maxIptvAccounts);
        m.put("maxConcurrentStreams", plan.maxConcurrentStreams);
        m.put("storageGb", plan.storageGb);
        m.put("active", plan.active);
        m.put("entitlements", plan.entitlements == null
                ? java.util.List.of()
                : plan.entitlements.stream().map(ApiMappers::entitlement).toList());
        m.put("accessSummary", accessSummary(plan));
        return m;
    }

    public static Map<String, Object> entitlement(PlanEntitlement entitlement) {
        Map<String, Object> m = map();
        m.put("id", entitlement.id);
        m.put("mode", entitlement.mode);
        m.put("contentType", entitlement.contentType);
        m.put("categoryId", entitlement.categoryId);
        m.put("keyword", entitlement.keyword);
        m.put("label", entitlement.label);
        m.put("enabled", entitlement.enabled);
        m.put("priority", entitlement.priority);
        return m;
    }

    private static String accessSummary(Plan plan) {
        if (plan.entitlements == null || plan.entitlements.isEmpty()) {
            return "Catalogue complet";
        }
        return plan.entitlements.stream()
                .filter(entitlement -> entitlement != null && entitlement.enabled)
                .map(entitlement -> entitlement.label)
                .filter(label -> label != null && !label.isBlank())
                .limit(3)
                .reduce((left, right) -> left + ", " + right)
                .orElse("Aucun acces actif");
    }

    public static Map<String, Object> subscription(Subscription subscription) {
        if (subscription == null) {
            return null;
        }
        Map<String, Object> m = map();
        m.put("id", subscription.id);
        m.put("organizationId", subscription.organization == null ? null : subscription.organization.id);
        m.put("plan", plan(subscription.plan));
        m.put("status", subscription.status);
        m.put("startedAt", subscription.startedAt);
        m.put("trialEndsAt", subscription.trialEndsAt);
        m.put("currentPeriodEnd", subscription.currentPeriodEnd);
        m.put("cancelAtPeriodEnd", subscription.cancelAtPeriodEnd);
        return m;
    }

    public static Map<String, Object> paymentMethod(PaymentMethod method) {
        Map<String, Object> m = map();
        m.put("id", method.id);
        m.put("code", method.code);
        m.put("name", method.name);
        m.put("instructions", method.instructions);
        m.put("active", method.active);
        return m;
    }

    public static Map<String, Object> payment(PaymentTransaction payment) {
        Map<String, Object> m = map();
        m.put("id", payment.id);
        m.put("reference", payment.paymentReference);
        m.put("organizationId", payment.organization == null ? null : payment.organization.id);
        m.put("plan", payment.plan == null ? null : plan(payment.plan));
        m.put("paymentMethod", payment.paymentMethod == null ? null : paymentMethod(payment.paymentMethod));
        m.put("amount", payment.amount);
        m.put("currency", payment.currency);
        m.put("status", payment.status);
        m.put("proofUrl", payment.proofUrl);
        m.put("rejectionReason", payment.rejectionReason);
        m.put("expiresAt", payment.expiresAt);
        m.put("verifiedAt", payment.verifiedAt);
        m.put("createdAt", payment.createdAt);
        return m;
    }

    public static Map<String, Object> invoice(Invoice invoice) {
        Map<String, Object> m = map();
        m.put("id", invoice.id);
        m.put("invoiceNumber", invoice.invoiceNumber);
        m.put("organizationId", invoice.organization == null ? null : invoice.organization.id);
        m.put("paymentId", invoice.paymentTransaction == null ? null : invoice.paymentTransaction.id);
        m.put("amount", invoice.amount);
        m.put("currency", invoice.currency);
        m.put("status", invoice.status);
        m.put("issuedAt", invoice.issuedAt);
        m.put("sentAt", invoice.sentAt);
        m.put("downloadedAt", invoice.downloadedAt);
        return m;
    }

    public static Map<String, Object> iptvAccount(IptvAccount account) {
        Map<String, Object> m = map();
        m.put("id", account.id);
        m.put("name", account.name);
        m.put("type", account.accountType);
        m.put("baseUrl", account.baseUrl);
        m.put("playlistUrl", account.playlistUrl);
        m.put("active", account.active);
        m.put("disabled", account.disabled);
        m.put("expiresAt", account.expiresAt);
        m.put("maxStreams", account.maxStreams);
        m.put("activeStreams", account.activeStreams);
        m.put("failureCount", account.failureCount);
        m.put("lastHealthStatus", account.lastHealthStatus);
        m.put("disabledReason", account.disabledReason);
        return m;
    }

    public static Map<String, Object> session(UserSession session) {
        Map<String, Object> m = map();
        m.put("id", session.id);
        m.put("token", session.sessionToken);
        m.put("userId", session.user == null ? null : session.user.id);
        m.put("organizationId", session.organization == null ? null : session.organization.id);
        m.put("iptvAccountId", session.iptvAccount == null ? null : session.iptvAccount.id);
        m.put("contentType", session.contentType);
        m.put("itemId", session.itemId);
        m.put("playbackQuality", session.playbackQuality);
        m.put("status", session.status);
        m.put("openedAt", session.openedAt);
        m.put("lastHeartbeatAt", session.lastHeartbeatAt);
        m.put("closedAt", session.closedAt);
        return m;
    }

    public static Map<String, Object> ticket(SupportTicket ticket) {
        Map<String, Object> m = map();
        m.put("id", ticket.id);
        m.put("organizationId", ticket.organization == null ? null : ticket.organization.id);
        m.put("userId", ticket.user == null ? null : ticket.user.id);
        m.put("assignedToId", ticket.assignedTo == null ? null : ticket.assignedTo.id);
        m.put("subject", ticket.subject);
        m.put("priority", ticket.priority);
        m.put("status", ticket.status);
        m.put("createdAt", ticket.createdAt);
        m.put("updatedAt", ticket.updatedAt);
        return m;
    }

    public static Map<String, Object> message(SupportMessage message) {
        Map<String, Object> m = map();
        m.put("id", message.id);
        m.put("ticketId", message.ticket == null ? null : message.ticket.id);
        m.put("author", user(message.author));
        m.put("body", message.body);
        m.put("internal", message.internalMessage);
        m.put("createdAt", message.createdAt);
        return m;
    }

    public static Map<String, Object> notification(UserNotification notification) {
        Map<String, Object> m = map();
        m.put("id", notification.id);
        m.put("type", notification.type);
        m.put("title", notification.title);
        m.put("body", notification.body);
        m.put("targetUrl", notification.targetUrl);
        m.put("publishedAt", notification.publishedAt);
        m.put("readAt", notification.readAt);
        return m;
    }

    public static Map<String, Object> uptime(UptimeCheck check) {
        Map<String, Object> m = map();
        m.put("id", check.id);
        m.put("name", check.name);
        m.put("url", check.url);
        m.put("method", check.method);
        m.put("enabled", check.enabled);
        m.put("lastStatus", check.lastStatus);
        m.put("lastCheckedAt", check.lastCheckedAt);
        m.put("lastLatencyMs", check.lastLatencyMs);
        m.put("lastError", check.lastError);
        return m;
    }

    public static Map<String, Object> audit(AuditLog log) {
        Map<String, Object> m = map();
        m.put("id", log.id);
        m.put("userId", log.user == null ? null : log.user.id);
        m.put("action", log.action);
        m.put("subjectType", log.subjectType);
        m.put("subjectId", log.subjectId);
        m.put("ipAddress", log.ipAddress);
        m.put("metadata", log.metadata);
        m.put("createdAt", log.createdAt);
        return m;
    }

    private static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }
}
