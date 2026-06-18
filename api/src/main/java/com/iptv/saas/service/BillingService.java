package com.iptv.saas.service;

import com.iptv.saas.domain.*;
import com.iptv.saas.repository.*;
import com.iptv.saas.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.List;
import java.util.UUID;

@Service
public class BillingService {
    private final PlanRepository plans;
    private final PaymentMethodRepository paymentMethods;
    private final PaymentTransactionRepository payments;
    private final SubscriptionRepository subscriptions;
    private final OrganizationService organizationService;
    private final InvoiceService invoiceService;
    private final TelegramAlertService telegram;
    private final AuditService audit;
    private final String defaultTrialPlan;
    private final int defaultTrialDays;
    private final int paymentExpiresHours;

    public BillingService(
            PlanRepository plans,
            PaymentMethodRepository paymentMethods,
            PaymentTransactionRepository payments,
            SubscriptionRepository subscriptions,
            OrganizationService organizationService,
            InvoiceService invoiceService,
            TelegramAlertService telegram,
            AuditService audit,
            @Value("${app.billing.default-trial-plan:free}") String defaultTrialPlan,
            @Value("${app.billing.default-trial-days:7}") int defaultTrialDays,
            @Value("${app.billing.payment-request-expires-hours:24}") int paymentExpiresHours
    ) {
        this.plans = plans;
        this.paymentMethods = paymentMethods;
        this.payments = payments;
        this.subscriptions = subscriptions;
        this.organizationService = organizationService;
        this.invoiceService = invoiceService;
        this.telegram = telegram;
        this.audit = audit;
        this.defaultTrialPlan = defaultTrialPlan;
        this.defaultTrialDays = defaultTrialDays;
        this.paymentExpiresHours = paymentExpiresHours;
    }

    @Transactional(readOnly = true)
    public List<Plan> publicPlans() {
        return plans.findByActiveTrueOrderByPriceMonthlyAsc();
    }

    @Transactional(readOnly = true)
    public List<PaymentMethod> publicPaymentMethods() {
        return paymentMethods.findByActiveTrue();
    }

    @Transactional(readOnly = true)
    public Subscription currentSubscription(UserEntity user) {
        Organization organization = organizationService.currentOrganization(user);
        return subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(organization).orElse(null);
    }

    @Transactional
    public Subscription startTrial(UserEntity user, String planCode) {
        Organization organization = organizationService.currentOrganization(user);
        Subscription existing = subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(organization).orElse(null);
        if (subscriptionUsable(existing)) {
            return existing;
        }
        String selectedPlan = planCode == null || planCode.isBlank() ? defaultTrialPlan : planCode;
        Plan plan = plans.findByCodeAndActiveTrue(selectedPlan)
                .orElseThrow(() -> ApiException.notFound("Plan introuvable"));
        Subscription subscription = new Subscription();
        subscription.organization = organization;
        subscription.plan = plan;
        subscription.startedAt = Instant.now();
        if (plan.priceMonthly == null || plan.priceMonthly.signum() == 0) {
            subscription.status = Enums.SubscriptionStatus.ACTIVE;
            subscription.currentPeriodEnd = Instant.now().plus(billingPeriodDays(plan), ChronoUnit.DAYS);
        } else {
            int days = trialDays(plan);
            if (days <= 0) {
                throw ApiException.paymentRequired("Cette formule ne propose pas d'essai gratuit");
            }
            if (subscriptions.existsByOrganizationAndPlanAndTrialEndsAtIsNotNull(organization, plan)) {
                throw ApiException.paymentRequired("Essai deja utilise pour cette formule. Veuillez valider un paiement.");
            }
            subscription.status = Enums.SubscriptionStatus.TRIALING;
            subscription.trialEndsAt = Instant.now().plus(days, ChronoUnit.DAYS);
            subscription.currentPeriodEnd = subscription.trialEndsAt;
        }
        deactivateOtherOpenSubscriptions(organization, subscription);
        subscription = subscriptions.save(subscription);
        audit.log(
                user,
                subscription.status == Enums.SubscriptionStatus.TRIALING
                        ? "billing.trial.started"
                        : "billing.subscription.started",
                "Subscription",
                subscription.id,
                plan.code
        );
        return subscription;
    }

    @Transactional
    public PaymentTransaction createPayment(UserEntity user, String planCode, String paymentMethodCode, String proofUrl) {
        Organization organization = organizationService.currentOrganization(user);
        Plan plan = plans.findByCodeAndActiveTrue(planCode).orElseThrow(() -> ApiException.notFound("Plan introuvable"));
        PaymentMethod method = paymentMethods.findByCode(paymentMethodCode)
                .orElseThrow(() -> ApiException.notFound("Moyen de paiement introuvable"));
        PaymentTransaction payment = new PaymentTransaction();
        payment.organization = organization;
        payment.user = user;
        payment.plan = plan;
        payment.paymentMethod = method;
        payment.paymentReference = "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        payment.amount = plan.priceMonthly == null ? BigDecimal.ZERO : plan.priceMonthly;
        payment.currency = plan.currency;
        payment.status = Enums.PaymentStatus.PENDING;
        payment.proofUrl = proofUrl;
        payment.expiresAt = Instant.now().plus(paymentExpiresHours, ChronoUnit.HOURS);
        payment = payments.save(payment);
        telegram.send(
                "Nouveau paiement en attente",
                payment.paymentReference + " - " + payment.amount + " " + payment.currency,
                List.of(List.of(
                        new TelegramAlertService.Action("Valider #" + payment.id, "confirm:verify_payment:" + payment.id),
                        new TelegramAlertService.Action("Refuser #" + payment.id, "confirm:reject_payment:" + payment.id)
                ))
        );
        audit.log(user, "billing.payment.requested", "PaymentTransaction", payment.id, payment.paymentReference);
        return payment;
    }

    @Transactional(readOnly = true)
    public List<PaymentTransaction> history(UserEntity user) {
        Organization organization = organizationService.currentOrganization(user);
        return payments.findByOrganizationOrderByCreatedAtDesc(organization);
    }

    @Transactional
    public Subscription changePlan(UserEntity user, String planCode) {
        Organization organization = organizationService.currentOrganization(user);
        Plan plan = plans.findByCodeAndActiveTrue(planCode).orElseThrow(() -> ApiException.notFound("Plan introuvable"));
        Subscription subscription = subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(organization)
                .orElseGet(() -> {
                    Subscription created = new Subscription();
                    created.organization = organization;
                    created.startedAt = Instant.now();
                    return created;
                });
        if (subscriptionUsable(subscription)
                && subscription.plan != null
                && Objects.equals(subscription.plan.id, plan.id)) {
            return subscription;
        }
        subscription.plan = plan;
        if (plan.priceMonthly == null || plan.priceMonthly.signum() == 0) {
            subscription.status = Enums.SubscriptionStatus.ACTIVE;
            subscription.trialEndsAt = null;
            subscription.currentPeriodEnd = Instant.now().plus(billingPeriodDays(plan), ChronoUnit.DAYS);
        } else {
            int days = trialDays(plan);
            if (days <= 0 || subscriptions.existsByOrganizationAndPlanAndTrialEndsAtIsNotNull(organization, plan)) {
                throw ApiException.paymentRequired("Paiement requis pour activer cette formule");
            }
            subscription.status = Enums.SubscriptionStatus.TRIALING;
            subscription.trialEndsAt = Instant.now().plus(days, ChronoUnit.DAYS);
            subscription.currentPeriodEnd = subscription.trialEndsAt;
        }
        deactivateOtherOpenSubscriptions(organization, subscription);
        subscription = subscriptions.save(subscription);
        audit.log(user, "billing.plan.changed", "Subscription", subscription.id, plan.code);
        return subscription;
    }

    @Transactional
    public Subscription cancel(UserEntity user) {
        Organization organization = organizationService.currentOrganization(user);
        Subscription subscription = subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(organization)
                .orElseThrow(() -> ApiException.notFound("Abonnement introuvable"));
        subscription.cancelAtPeriodEnd = true;
        subscription.status = Enums.SubscriptionStatus.CANCELLED;
        audit.log(user, "billing.subscription.cancelled", "Subscription", subscription.id, subscription.plan.code);
        return subscriptions.save(subscription);
    }

    @Transactional
    public PaymentTransaction verifyPayment(UserEntity admin, Long paymentId) {
        PaymentTransaction payment = payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Paiement introuvable"));
        if (payment.status != Enums.PaymentStatus.PENDING) {
            throw ApiException.validation("Ce paiement n'est pas en attente");
        }
        payment.status = Enums.PaymentStatus.VERIFIED;
        payment.verifiedAt = Instant.now();
        payment.verifiedBy = admin;
        payment = payments.save(payment);
        activateSubscription(payment);
        Invoice invoice = invoiceService.createForPayment(payment);
        invoiceService.resend(admin, invoice.id);
        telegram.send("Paiement valide", payment.paymentReference + " - " + payment.amount + " " + payment.currency);
        audit.log(admin, "billing.payment.verified", "PaymentTransaction", payment.id, payment.paymentReference);
        return payment;
    }

    @Transactional
    public PaymentTransaction rejectPayment(UserEntity admin, Long paymentId, String reason) {
        PaymentTransaction payment = payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Paiement introuvable"));
        if (payment.status != Enums.PaymentStatus.PENDING) {
            throw ApiException.validation("Ce paiement n'est pas en attente");
        }
        payment.status = Enums.PaymentStatus.REJECTED;
        payment.rejectionReason = reason == null || reason.isBlank() ? "Rejete par l'administration" : reason;
        payment.verifiedBy = admin;
        payment.verifiedAt = Instant.now();
        telegram.send(
                "Paiement rejete",
                payment.paymentReference + " - " + payment.rejectionReason
        );
        audit.log(admin, "billing.payment.rejected", "PaymentTransaction", payment.id, payment.rejectionReason);
        return payments.save(payment);
    }

    @Transactional
    public Plan savePlan(Long id, String code, String name, BigDecimal price, String currency, Integer trialDays,
                         Integer billingPeriodDays, String description, String highlight, Integer maxUsers,
                         Integer maxIptvAccounts, Integer maxConcurrentStreams, Integer storageGb, Boolean active,
                         List<PlanEntitlementSpec> entitlementSpecs) {
        Plan plan = id == null ? new Plan() : plans.findById(id).orElseThrow(() -> ApiException.notFound("Plan introuvable"));
        if (code != null && !code.isBlank()) plan.code = code;
        if (name != null && !name.isBlank()) plan.name = name;
        if (price != null) plan.priceMonthly = price;
        if (currency != null && !currency.isBlank()) plan.currency = currency;
        if (trialDays != null) plan.trialDays = Math.max(0, trialDays);
        if (billingPeriodDays != null) plan.billingPeriodDays = Math.max(1, billingPeriodDays);
        if (description != null) plan.description = description;
        if (highlight != null) plan.highlight = highlight;
        if (maxUsers != null) plan.maxUsers = maxUsers;
        if (maxIptvAccounts != null) plan.maxIptvAccounts = maxIptvAccounts;
        if (maxConcurrentStreams != null) plan.maxConcurrentStreams = maxConcurrentStreams;
        if (storageGb != null) plan.storageGb = storageGb;
        if (active != null) plan.active = active;
        if (entitlementSpecs != null) {
            plan.entitlements.clear();
            for (int index = 0; index < entitlementSpecs.size(); index += 1) {
                PlanEntitlementSpec spec = entitlementSpecs.get(index);
                PlanEntitlement entitlement = entitlement(spec, index);
                entitlement.plan = plan;
                plan.entitlements.add(entitlement);
            }
        }
        return plans.save(plan);
    }

    @Transactional
    public PaymentMethod savePaymentMethod(Long id, String code, String name, String instructions, Boolean active) {
        PaymentMethod method = id == null ? new PaymentMethod() : paymentMethods.findById(id)
                .orElseThrow(() -> ApiException.notFound("Moyen de paiement introuvable"));
        if (code != null && !code.isBlank()) method.code = code;
        if (name != null && !name.isBlank()) method.name = name;
        if (instructions != null) method.instructions = instructions;
        if (active != null) method.active = active;
        return paymentMethods.save(method);
    }

    @Transactional(readOnly = true)
    public List<PaymentTransaction> allPayments() {
        return payments.findTop10ByOrderByCreatedAtDesc();
    }

    private PlanEntitlement entitlement(PlanEntitlementSpec spec, int fallbackPriority) {
        PlanEntitlement entitlement = new PlanEntitlement();
        Enums.PlanEntitlementMode mode = spec == null || spec.mode() == null
                ? Enums.PlanEntitlementMode.ALL
                : spec.mode();
        entitlement.mode = mode;
        entitlement.contentType = normalizeContentType(spec == null ? null : spec.contentType());
        entitlement.categoryId = blankToNull(spec == null ? null : spec.categoryId());
        entitlement.keyword = blankToNull(spec == null ? null : spec.keyword());
        entitlement.label = blankToNull(spec == null ? null : spec.label());
        entitlement.enabled = spec == null || spec.enabled() == null || spec.enabled();
        entitlement.priority = spec == null || spec.priority() == null ? fallbackPriority : spec.priority();
        if (entitlement.label == null) {
            entitlement.label = defaultEntitlementLabel(entitlement);
        }
        if (mode == Enums.PlanEntitlementMode.CATEGORY && entitlement.categoryId == null) {
            throw ApiException.validation("Une regle par categorie doit contenir une categorie");
        }
        if (mode == Enums.PlanEntitlementMode.ADDON && entitlement.categoryId == null) {
            throw ApiException.validation("Une regle par add-on doit contenir un add-on");
        }
        if (mode == Enums.PlanEntitlementMode.CONNECTOR && entitlement.keyword == null) {
            throw ApiException.validation("Une regle par connecteur doit contenir un connecteur");
        }
        if (mode == Enums.PlanEntitlementMode.KEYWORD && entitlement.keyword == null) {
            throw ApiException.validation("Une regle automatique doit contenir un mot-cle");
        }
        return entitlement;
    }

    private String defaultEntitlementLabel(PlanEntitlement entitlement) {
        return switch (entitlement.mode) {
            case ALL -> "Tout le catalogue";
            case TYPE -> "Type " + entitlement.contentType;
            case CATEGORY -> "Categorie " + entitlement.categoryId;
            case KEYWORD -> "Mot-cle " + entitlement.keyword;
            case ADDON -> "Add-on " + entitlement.categoryId;
            case CONNECTOR -> "Connecteur " + entitlement.keyword;
        };
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "all";
        }
        String normalized = contentType.trim().toLowerCase();
        return switch (normalized) {
            case "live", "movie", "series", "all" -> normalized;
            default -> "all";
        };
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int trialDays(Plan plan) {
        if (plan == null) {
            return Math.max(0, defaultTrialDays);
        }
        return plan.trialDays > 0 ? plan.trialDays : 0;
    }

    private int billingPeriodDays(Plan plan) {
        return plan == null || plan.billingPeriodDays == null || plan.billingPeriodDays <= 0 ? 30 : plan.billingPeriodDays;
    }

    private boolean subscriptionUsable(Subscription subscription) {
        if (subscription == null) {
            return false;
        }
        boolean validStatus = subscription.status == Enums.SubscriptionStatus.ACTIVE
                || subscription.status == Enums.SubscriptionStatus.TRIALING;
        Instant end = subscription.status == Enums.SubscriptionStatus.TRIALING
                ? subscription.trialEndsAt
                : subscription.currentPeriodEnd;
        return validStatus && (end == null || end.isAfter(Instant.now()));
    }

    private void activateSubscription(PaymentTransaction payment) {
        Subscription subscription = subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(payment.organization)
                .orElseGet(() -> {
                    Subscription created = new Subscription();
                    created.organization = payment.organization;
                    created.startedAt = Instant.now();
                    return created;
                });
        subscription.plan = payment.plan;
        subscription.status = Enums.SubscriptionStatus.ACTIVE;
        subscription.cancelAtPeriodEnd = false;
        subscription.currentPeriodEnd = Instant.now().plus(billingPeriodDays(payment.plan), ChronoUnit.DAYS);
        if (subscription.startedAt == null) {
            subscription.startedAt = Instant.now();
        }
        deactivateOtherOpenSubscriptions(payment.organization, subscription);
        subscriptions.save(subscription);
    }

    private void deactivateOtherOpenSubscriptions(Organization organization, Subscription keep) {
        if (organization == null) {
            return;
        }
        List<Subscription> open = subscriptions.findByOrganizationAndStatusIn(
                organization,
                List.of(Enums.SubscriptionStatus.ACTIVE, Enums.SubscriptionStatus.TRIALING, Enums.SubscriptionStatus.PAST_DUE)
        );
        for (Subscription subscription : open) {
            if (keep.id != null && Objects.equals(subscription.id, keep.id)) {
                continue;
            }
            if (keep.id == null && subscription == keep) {
                continue;
            }
            subscription.status = Enums.SubscriptionStatus.CANCELLED;
            subscription.cancelAtPeriodEnd = true;
        }
        if (!open.isEmpty()) {
            subscriptions.saveAll(open);
        }
    }

    public record PlanEntitlementSpec(
            Enums.PlanEntitlementMode mode,
            String contentType,
            String categoryId,
            String keyword,
            String label,
            Boolean enabled,
            Integer priority
    ) {
    }
}
