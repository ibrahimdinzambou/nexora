package com.iptv.saas.web;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.repository.InvoiceRepository;
import com.iptv.saas.repository.OrganizationRepository;
import com.iptv.saas.repository.SubscriptionRepository;
import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.service.AdminDashboardService;
import com.iptv.saas.web.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/saas")
public class AdminSaasController {
    private final AdminDashboardService dashboard;
    private final OrganizationRepository organizations;
    private final SubscriptionRepository subscriptions;
    private final InvoiceRepository invoices;

    public AdminSaasController(
            AdminDashboardService dashboard,
            OrganizationRepository organizations,
            SubscriptionRepository subscriptions,
            InvoiceRepository invoices
    ) {
        this.dashboard = dashboard;
        this.organizations = organizations;
        this.subscriptions = subscriptions;
        this.invoices = invoices;
    }

    @GetMapping("/dashboard")
    public Object dashboard() {
        return Responses.ok(dashboard.dashboard());
    }

    @GetMapping("/customers")
    public Object customers() {
        return Responses.ok(organizations.findAll().stream().map(ApiMappers::organization).toList());
    }

    @GetMapping("/subscriptions")
    public Object subscriptions() {
        return Responses.ok(this.subscriptions.findAll().stream().map(ApiMappers::subscription).toList());
    }

    @GetMapping("/invoices")
    public Object invoices() {
        return Responses.ok(this.invoices.findAll().stream().map(ApiMappers::invoice).toList());
    }

    @PostMapping("/customers/{id}/suspend")
    public Object suspend(@PathVariable Long id) {
        var organization = organizations.findById(id).orElseThrow(() -> ApiException.notFound("Client introuvable"));
        organization.status = Enums.OrganizationStatus.SUSPENDED;
        organizations.save(organization);
        return Responses.ok(ApiMappers.organization(organization));
    }

    @PostMapping("/customers/{id}/reactivate")
    public Object reactivate(@PathVariable Long id) {
        var organization = organizations.findById(id).orElseThrow(() -> ApiException.notFound("Client introuvable"));
        organization.status = Enums.OrganizationStatus.ACTIVE;
        organizations.save(organization);
        return Responses.ok(ApiMappers.organization(organization));
    }

    @GetMapping("/whoami")
    public Object whoami() {
        return Responses.ok(ApiMappers.user(SecurityUtils.currentUser()));
    }
}
