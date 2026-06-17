package com.iptv.saas.web;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.repository.PaymentMethodRepository;
import com.iptv.saas.repository.PaymentTransactionRepository;
import com.iptv.saas.repository.PlanRepository;
import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.service.BillingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/admin/billing")
public class AdminBillingController {
    private final BillingService billing;
    private final PlanRepository plans;
    private final PaymentMethodRepository paymentMethods;
    private final PaymentTransactionRepository payments;

    public AdminBillingController(
            BillingService billing,
            PlanRepository plans,
            PaymentMethodRepository paymentMethods,
            PaymentTransactionRepository payments
    ) {
        this.billing = billing;
        this.plans = plans;
        this.paymentMethods = paymentMethods;
        this.payments = payments;
    }

    @GetMapping("/plans")
    public Object plans() {
        return Responses.ok(this.plans.findAll().stream().map(ApiMappers::plan).toList());
    }

    @PostMapping("/plans")
    public Object createPlan(@Valid @RequestBody PlanRequest request) {
        return Responses.ok(ApiMappers.plan(billing.savePlan(
                null,
                request.code(),
                request.name(),
                request.priceMonthly(),
                request.currency(),
                request.trialDays(),
                request.billingPeriodDays(),
                request.description(),
                request.highlight(),
                request.maxUsers(),
                request.maxIptvAccounts(),
                request.maxConcurrentStreams(),
                request.storageGb(),
                request.active(),
                entitlementSpecs(request.entitlements())
        )));
    }

    @PutMapping("/plans/{id}")
    public Object updatePlan(@PathVariable Long id, @RequestBody PlanRequest request) {
        return Responses.ok(ApiMappers.plan(billing.savePlan(
                id,
                request.code(),
                request.name(),
                request.priceMonthly(),
                request.currency(),
                request.trialDays(),
                request.billingPeriodDays(),
                request.description(),
                request.highlight(),
                request.maxUsers(),
                request.maxIptvAccounts(),
                request.maxConcurrentStreams(),
                request.storageGb(),
                request.active(),
                entitlementSpecs(request.entitlements())
        )));
    }

    @GetMapping("/payment-methods")
    public Object paymentMethods() {
        return Responses.ok(this.paymentMethods.findAll().stream().map(ApiMappers::paymentMethod).toList());
    }

    @PostMapping("/payment-methods")
    public Object createPaymentMethod(@Valid @RequestBody PaymentMethodRequest request) {
        return Responses.ok(ApiMappers.paymentMethod(billing.savePaymentMethod(
                null,
                request.code(),
                request.name(),
                request.instructions(),
                request.active()
        )));
    }

    @PutMapping("/payment-methods/{id}")
    public Object updatePaymentMethod(@PathVariable Long id, @RequestBody PaymentMethodRequest request) {
        return Responses.ok(ApiMappers.paymentMethod(billing.savePaymentMethod(
                id,
                request.code(),
                request.name(),
                request.instructions(),
                request.active()
        )));
    }

    @GetMapping("/payments")
    public Object payments() {
        return Responses.ok(this.payments.findAll().stream().map(ApiMappers::payment).toList());
    }

    @PostMapping("/payments/{id}/verify")
    public Object verify(@PathVariable Long id) {
        return Responses.ok(ApiMappers.payment(billing.verifyPayment(SecurityUtils.currentUser(), id)));
    }

    @PostMapping("/payments/{id}/reject")
    public Object reject(@PathVariable Long id, @RequestBody(required = false) RejectRequest request) {
        return Responses.ok(ApiMappers.payment(billing.rejectPayment(
                SecurityUtils.currentUser(),
                id,
                request == null ? null : request.reason()
        )));
    }

    public record PlanRequest(
            @NotBlank String code,
            @NotBlank String name,
            BigDecimal priceMonthly,
            String currency,
            Integer trialDays,
            Integer billingPeriodDays,
            String description,
            String highlight,
            Integer maxUsers,
            Integer maxIptvAccounts,
            Integer maxConcurrentStreams,
            Integer storageGb,
            Boolean active,
            List<EntitlementRequest> entitlements
    ) {
    }

    public record EntitlementRequest(
            Enums.PlanEntitlementMode mode,
            String contentType,
            String categoryId,
            String keyword,
            String label,
            Boolean enabled,
            Integer priority
    ) {
    }

    public record PaymentMethodRequest(@NotBlank String code, @NotBlank String name, String instructions, Boolean active) {
    }

    public record RejectRequest(String reason) {
    }

    private List<BillingService.PlanEntitlementSpec> entitlementSpecs(List<EntitlementRequest> entitlements) {
        if (entitlements == null) {
            return null;
        }
        return entitlements.stream()
                .map(entitlement -> new BillingService.PlanEntitlementSpec(
                        entitlement.mode(),
                        entitlement.contentType(),
                        entitlement.categoryId(),
                        entitlement.keyword(),
                        entitlement.label(),
                        entitlement.enabled(),
                        entitlement.priority()
                ))
                .toList();
    }
}
