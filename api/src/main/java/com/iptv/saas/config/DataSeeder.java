package com.iptv.saas.config;

import com.iptv.saas.domain.*;
import com.iptv.saas.repository.*;
import com.iptv.saas.service.CommunityAddonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Configuration
public class DataSeeder {
    private static final Logger LOGGER = LoggerFactory.getLogger(DataSeeder.class);
    private static final String USER_SUPPLIED_LICENSE = "Manifeste fourni par l'utilisateur";

    @Bean
    CommandLineRunner seedData(
            UserRepository users,
            OrganizationRepository organizations,
            OrganizationMembershipRepository memberships,
            PlanRepository plans,
            PaymentMethodRepository paymentMethods,
            SubscriptionRepository subscriptions,
            IptvAccountRepository iptvAccounts,
            LegalDocumentRepository legalDocuments,
            UptimeCheckRepository uptimeChecks,
            CommunityAddonRepository communityAddons,
            CommunityAddonService communityAddonService,
            PasswordEncoder passwordEncoder
    ) {
        return args -> {
            Plan free = plans.findByCode("free").orElseGet(() -> plans.save(plan("free", "Free", "0.00", 1, 1, 1, 1)));
            Plan basic = plans.findByCode("basic").orElseGet(() -> plans.save(plan("basic", "Basic", "5000.00", 2, 1, 1, 5)));
            Plan sports = plans.findByCode("sports").orElseGet(() -> plans.save(plan("sports", "Sports", "3500.00", 2, 1, 1, 5)));
            Plan pro = plans.findByCode("pro").orElseGet(() -> plans.save(plan("pro", "Pro", "15000.00", 5, 3, 3, 20)));
            Plan enterprise = plans.findByCode("enterprise").orElseGet(() -> plans.save(plan("enterprise", "Enterprise", "50000.00", 25, 10, 10, 100)));
            tunePlans(plans, free, basic, sports, pro, enterprise);

            paymentMethods.findByCode("mobile_money").orElseGet(() -> paymentMethods.save(method(
                    "mobile_money",
                    "Mobile Money",
                    "Envoyez le paiement a votre numero marchand puis renseignez la preuve."
            )));
            paymentMethods.findByCode("bank_transfer").orElseGet(() -> paymentMethods.save(method(
                    "bank_transfer",
                    "Virement bancaire",
                    "Effectuez un virement et ajoutez la reference dans la demande de paiement."
            )));

            UserEntity admin = users.findByEmailIgnoreCase("admin@example.com").orElseGet(() -> {
                UserEntity user = new UserEntity();
                user.name = "Admin";
                user.email = "admin@example.com";
                user.passwordHash = passwordEncoder.encode("password");
                user.role = Enums.UserRole.SUPER_ADMIN;
                user.active = true;
                user.emailVerified = true;
                return users.save(user);
            });

            UserEntity test = users.findByEmailIgnoreCase("test@example.com").orElseGet(() -> {
                UserEntity user = new UserEntity();
                user.name = "Test User";
                user.email = "test@example.com";
                user.passwordHash = passwordEncoder.encode("password");
                user.role = Enums.UserRole.USER;
                user.active = true;
                user.emailVerified = true;
                return users.save(user);
            });

            Organization org = organizations.findBySlug("demo-organization").orElseGet(() -> {
                Organization organization = new Organization();
                organization.name = "Demo Organization";
                organization.slug = "demo-organization";
                organization.owner = test;
                organization.billingEmail = test.email;
                organization.status = Enums.OrganizationStatus.ACTIVE;
                return organizations.save(organization);
            });
            if (test.currentOrganization == null) {
                test.currentOrganization = org;
                users.save(test);
            }
            if (admin.currentOrganization == null) {
                admin.currentOrganization = org;
                users.save(admin);
            }
            if (!memberships.existsByOrganizationAndUser(org, test)) {
                OrganizationMembership membership = new OrganizationMembership();
                membership.organization = org;
                membership.user = test;
                membership.role = "owner";
                memberships.save(membership);
            }

            if (subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(org).isEmpty()) {
                Subscription subscription = new Subscription();
                subscription.organization = org;
                subscription.plan = free;
                subscription.status = Enums.SubscriptionStatus.ACTIVE;
                subscription.startedAt = Instant.now();
                subscription.currentPeriodEnd = Instant.now().plus(365, ChronoUnit.DAYS);
                subscriptions.save(subscription);
            }

            if (iptvAccounts.count() == 0) {
                IptvAccount account = new IptvAccount();
                account.name = "Demo Xtream";
                account.accountType = Enums.IptvAccountType.XTREAM;
                account.baseUrl = "https://example.com";
                account.username = "demo";
                account.password = "demo";
                account.maxStreams = 5;
                account.active = true;
                account.expiresAt = Instant.now().plus(365, ChronoUnit.DAYS);
                account.lastHealthStatus = "ok";
                iptvAccounts.save(account);
            }

            legalDocuments.findByDocumentType("terms").orElseGet(() -> legalDocuments.save(legal(
                    "terms",
                    "Conditions d'utilisation",
                    "Conditions legales de demonstration."
            )));
            legalDocuments.findByDocumentType("privacy").orElseGet(() -> legalDocuments.save(legal(
                    "privacy",
                    "Confidentialite",
                    "Politique de confidentialite de demonstration."
            )));

            if (uptimeChecks.count() == 0) {
                UptimeCheck check = new UptimeCheck();
                check.name = "API Health";
                check.url = "http://localhost:8080/actuator/health";
                check.method = "GET";
                check.enabled = true;
                uptimeChecks.save(check);
            }

            seedDefaultAddon(
                    communityAddons,
                    communityAddonService,
                    "https://addon.notorrent2.workers.dev/manifest.json",
                    "addon.notorrent2.workers.dev",
                    false
            );
            seedDefaultAddon(
                    communityAddons,
                    communityAddonService,
                    "https://hdhub.thevolecitor.qzz.io/eyJ0b3Jib3giOiJ1bnNldCIsInF1YWxpdGllcyI6IjIxNjBwLDEwODBwLDcyMHAiLCJzb3J0IjoiZGVzYyJ9/manifest.json",
                    ".hwmce.com,.fodcyy.com,.bhcxy.com,.fhxod.com,hub.noirspy.buzz,hub.whistle.lat,cdn.fsl-buckets.work,.hubcloud.cx",
                    false
            );
        };
    }

    private void seedDefaultAddon(
            CommunityAddonRepository communityAddons,
            CommunityAddonService communityAddonService,
            String manifestUrl,
            String allowedStreamHosts,
            boolean adultContent
    ) {
        try {
            var existing = communityAddons.findByManifestUrl(manifestUrl);
            boolean created = existing.isEmpty();
            CommunityAddon addon = existing.orElseGet(() -> communityAddonService.install(
                    manifestUrl,
                    allowedStreamHosts,
                    USER_SUPPLIED_LICENSE,
                    manifestUrl,
                    adultContent
            ));
            String manifestHost = URI.create(manifestUrl).getHost();
            if (created
                    || addon.allowedStreamHosts == null
                    || addon.allowedStreamHosts.isBlank()
                    || addon.allowedStreamHosts.equalsIgnoreCase(manifestHost)) {
                addon.allowedStreamHosts = allowedStreamHosts;
            }
            if (addon.licenseName == null || addon.licenseName.isBlank()) {
                addon.licenseName = USER_SUPPLIED_LICENSE;
            }
            if (addon.licenseUrl == null || addon.licenseUrl.isBlank()) {
                addon.licenseUrl = manifestUrl;
            }
            addon.adultContent = adultContent;
            if (addon.status != Enums.AddonStatus.DISABLED) {
                addon.status = Enums.AddonStatus.APPROVED;
            }
            communityAddons.save(addon);
        } catch (RuntimeException exception) {
            LOGGER.warn("Impossible de preinstaller l'add-on {}: {}", manifestUrl, exception.getMessage());
        }
    }

    private Plan plan(String code, String name, String price, int users, int accounts, int streams, int storage) {
        Plan plan = new Plan();
        plan.code = code;
        plan.name = name;
        plan.priceMonthly = new BigDecimal(price);
        plan.currency = "XOF";
        plan.trialDays = 7;
        plan.maxUsers = users;
        plan.maxIptvAccounts = accounts;
        plan.maxConcurrentStreams = streams;
        plan.storageGb = storage;
        plan.active = true;
        return plan;
    }

    private void tunePlans(PlanRepository plans, Plan free, Plan basic, Plan sports, Plan pro, Plan enterprise) {
        free.description = "Une porte d'entree simple pour tester l'espace client.";
        free.highlight = "Decouverte";
        basic.description = "Le socle familial avec le catalogue general.";
        basic.highlight = "Essentiel";
        sports.description = "Acces automatique aux chaines et categories sportives.";
        sports.highlight = "3 jours d'essai";
        sports.trialDays = 3;
        sports.billingPeriodDays = 30;
        pro.description = "Plusieurs utilisateurs, plusieurs sources et plus de streams.";
        pro.highlight = "Le plus complet";
        enterprise.description = "Exploitation avancee pour grandes equipes et volumes eleves.";
        enterprise.highlight = "Sur mesure";
        if (sports.entitlements.isEmpty()) {
            sports.entitlements.add(entitlement(
                    sports,
                    Enums.PlanEntitlementMode.KEYWORD,
                    "all",
                    null,
                    "sport",
                    "Sports automatiques",
                    0
            ));
        }
        plans.save(free);
        plans.save(basic);
        plans.save(sports);
        plans.save(pro);
        plans.save(enterprise);
    }

    private PlanEntitlement entitlement(
            Plan plan,
            Enums.PlanEntitlementMode mode,
            String contentType,
            String categoryId,
            String keyword,
            String label,
            int priority
    ) {
        PlanEntitlement entitlement = new PlanEntitlement();
        entitlement.plan = plan;
        entitlement.mode = mode;
        entitlement.contentType = contentType;
        entitlement.categoryId = categoryId;
        entitlement.keyword = keyword;
        entitlement.label = label;
        entitlement.priority = priority;
        entitlement.enabled = true;
        return entitlement;
    }

    private PaymentMethod method(String code, String name, String instructions) {
        PaymentMethod method = new PaymentMethod();
        method.code = code;
        method.name = name;
        method.instructions = instructions;
        method.active = true;
        return method;
    }

    private LegalDocument legal(String type, String title, String content) {
        LegalDocument document = new LegalDocument();
        document.documentType = type;
        document.title = title;
        document.content = content;
        document.published = true;
        return document;
    }
}
