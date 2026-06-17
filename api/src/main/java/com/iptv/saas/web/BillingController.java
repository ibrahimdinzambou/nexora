package com.iptv.saas.web;

import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.service.BillingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BillingController {
    private final BillingService billing;

    public BillingController(BillingService billing) {
        this.billing = billing;
    }

    @GetMapping({"/api/billing/plans", "/api/v1/billing/plans"})
    public Object plans() {
        return Responses.ok(billing.publicPlans().stream().map(ApiMappers::plan).toList());
    }

    @GetMapping("/api/billing/payment-methods")
    public Object paymentMethods() {
        return Responses.ok(billing.publicPaymentMethods().stream().map(ApiMappers::paymentMethod).toList());
    }

    @GetMapping("/api/billing/current")
    public Object current() {
        return Responses.ok(ApiMappers.subscription(billing.currentSubscription(SecurityUtils.currentUser())));
    }

    @PostMapping("/api/billing/trial")
    public Object trial(@RequestBody(required = false) TrialRequest request) {
        return Responses.ok(ApiMappers.subscription(billing.startTrial(
                SecurityUtils.currentUser(),
                request == null ? null : request.planCode()
        )));
    }

    @PostMapping("/api/billing/payments")
    public Object createPayment(@Valid @RequestBody PaymentRequest request) {
        return Responses.ok(ApiMappers.payment(billing.createPayment(
                SecurityUtils.currentUser(),
                request.planCode(),
                request.paymentMethodCode(),
                request.proofUrl()
        )));
    }

    @GetMapping("/api/billing/payments")
    public Object payments() {
        return Responses.ok(billing.history(SecurityUtils.currentUser()).stream().map(ApiMappers::payment).toList());
    }

    @PostMapping("/api/billing/change-plan")
    public Object changePlan(@Valid @RequestBody ChangePlanRequest request) {
        return Responses.ok(ApiMappers.subscription(billing.changePlan(SecurityUtils.currentUser(), request.planCode())));
    }

    @PostMapping("/api/billing/cancel")
    public Object cancel() {
        return Responses.ok(ApiMappers.subscription(billing.cancel(SecurityUtils.currentUser())));
    }

    public record TrialRequest(String planCode) {
    }

    public record PaymentRequest(@NotBlank String planCode, @NotBlank String paymentMethodCode, String proofUrl) {
    }

    public record ChangePlanRequest(@NotBlank String planCode) {
    }
}
