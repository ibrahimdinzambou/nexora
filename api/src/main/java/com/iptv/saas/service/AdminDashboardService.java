package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.PaymentTransaction;
import com.iptv.saas.repository.*;
import com.iptv.saas.web.ApiMappers;
import com.iptv.saas.web.Responses;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class AdminDashboardService {
    private final OrganizationRepository organizations;
    private final SubscriptionRepository subscriptions;
    private final PaymentTransactionRepository payments;
    private final InvoiceRepository invoices;
    private final IptvAccountRepository iptvAccounts;
    private final UserSessionRepository sessions;
    private final SupportTicketRepository tickets;

    public AdminDashboardService(
            OrganizationRepository organizations,
            SubscriptionRepository subscriptions,
            PaymentTransactionRepository payments,
            InvoiceRepository invoices,
            IptvAccountRepository iptvAccounts,
            UserSessionRepository sessions,
            SupportTicketRepository tickets
    ) {
        this.organizations = organizations;
        this.subscriptions = subscriptions;
        this.payments = payments;
        this.invoices = invoices;
        this.iptvAccounts = iptvAccounts;
        this.sessions = sessions;
        this.tickets = tickets;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> dashboard() {
        BigDecimal verifiedRevenue = payments.findByStatusOrderByCreatedAtDesc(Enums.PaymentStatus.VERIFIED).stream()
                .map(payment -> payment.amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int streamCapacity = iptvAccounts.findAll().stream().mapToInt(account -> account.maxStreams).sum();
        Map<String, Object> body = Responses.map();
        body.put("clients", Map.of(
                "total", organizations.count(),
                "active", organizations.countByStatus(Enums.OrganizationStatus.ACTIVE),
                "suspended", organizations.countByStatus(Enums.OrganizationStatus.SUSPENDED)
        ));
        body.put("billing", Map.of(
                "mrr", verifiedRevenue,
                "activeSubscriptions", subscriptions.countByStatus(Enums.SubscriptionStatus.ACTIVE),
                "trialSubscriptions", subscriptions.countByStatus(Enums.SubscriptionStatus.TRIALING),
                "pastDueSubscriptions", subscriptions.countByStatus(Enums.SubscriptionStatus.PAST_DUE),
                "suspendedSubscriptions", subscriptions.countByStatus(Enums.SubscriptionStatus.SUSPENDED),
                "pendingPayments", payments.countByStatus(Enums.PaymentStatus.PENDING),
                "verifiedRevenue", verifiedRevenue
        ));
        body.put("iptv", Map.of(
                "accountsTotal", iptvAccounts.count(),
                "accountsActive", iptvAccounts.countByActiveTrue(),
                "streamsActive", sessions.countByStatus(Enums.SessionStatus.ACTIVE),
                "capacity", streamCapacity,
                "healthyAccounts", iptvAccounts.countByDisabledFalseAndActiveTrue()
        ));
        body.put("sessions", Map.of(
                "active", sessions.countByStatus(Enums.SessionStatus.ACTIVE),
                "total", sessions.count()
        ));
        body.put("support", Map.of(
                "openTickets", tickets.countByStatus(Enums.TicketStatus.OPEN),
                "recent", tickets.findTop10ByOrderByUpdatedAtDesc().stream().map(ApiMappers::ticket).toList()
        ));
        body.put("recentPayments", payments.findTop10ByOrderByCreatedAtDesc().stream().map(ApiMappers::payment).toList());
        body.put("alerts", List.of());
        body.put("revenueTrends", Map.of(
                "daily", List.of(Map.of("date", LocalDate.now().toString(), "amount", verifiedRevenue)),
                "weekly", List.of(Map.of("week", LocalDate.now().toString(), "amount", verifiedRevenue)),
                "monthly", List.of(Map.of("month", LocalDate.now().withDayOfMonth(1).toString(), "amount", verifiedRevenue))
        ));
        body.put("invoices", Map.of(
                "total", invoices.count(),
                "sent", invoices.countByStatus(Enums.InvoiceStatus.SENT),
                "downloaded", invoices.countByStatus(Enums.InvoiceStatus.DOWNLOADED)
        ));
        return body;
    }
}
