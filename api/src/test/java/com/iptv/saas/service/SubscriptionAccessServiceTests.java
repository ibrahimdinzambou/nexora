package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.Plan;
import com.iptv.saas.domain.PlanEntitlement;
import com.iptv.saas.domain.Subscription;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.SubscriptionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SubscriptionAccessServiceTests {
    private final SubscriptionAccessService access = new SubscriptionAccessService(
            mock(SubscriptionRepository.class),
            mock(OrganizationService.class)
    );

    @Test
    void keywordEntitlementGrantsMatchingCategoriesOnly() {
        UserEntity user = new UserEntity();
        Subscription subscription = activeSubscription(keywordEntitlement("sport", "all"));

        assertTrue(access.permits(user, subscription, "live", "live-sports-fr", "FR Sports", false));
        assertFalse(access.permits(user, subscription, "movie", "movie-action", "Action", false));
    }

    @Test
    void userCategoryAllowListNarrowsThePlanAccess() {
        UserEntity user = new UserEntity();
        user.allowedCategories = "[\"live-sports-fr\"]";
        Subscription subscription = activeSubscription(keywordEntitlement("sport", "all"));

        assertTrue(access.permits(user, subscription, "live", "live-sports-fr", "FR Sports", false));
        assertFalse(access.permits(user, subscription, "live", "live-sports-uk", "UK Sports", false));
    }

    @Test
    void adultContentNeedsAnExplicitCategoryEntitlement() {
        UserEntity user = new UserEntity();
        Subscription broadSubscription = activeSubscription(typeEntitlement("movie"));
        Subscription explicitSubscription = activeSubscription(categoryEntitlement("adult-movies"));

        assertFalse(access.permits(user, broadSubscription, "movie", "adult-movies", "Adult", true));
        assertTrue(access.permits(user, explicitSubscription, "movie", "adult-movies", "Adult", true));
    }

    @Test
    void addonEntitlementGrantsMatchingAddonCatalogs() {
        UserEntity user = new UserEntity();
        Subscription subscription = activeSubscription(addonEntitlement("addon:42"));

        assertTrue(access.permits(user, subscription, "movie", "addon-42-movie-cinema", "Cinema", false));
        assertFalse(access.permits(user, subscription, "movie", "addon-7-movie-cinema", "Cinema", false));
    }

    @Test
    void connectorEntitlementDoesNotGrantCatalogAccess() {
        UserEntity user = new UserEntity();
        Subscription subscription = activeSubscription(connectorEntitlement("telegram"));

        assertFalse(access.permits(user, subscription, "live", "live-news", "News", false));
    }

    private Subscription activeSubscription(PlanEntitlement entitlement) {
        Plan plan = new Plan();
        plan.entitlements.add(entitlement);
        entitlement.plan = plan;
        Subscription subscription = new Subscription();
        subscription.plan = plan;
        subscription.status = Enums.SubscriptionStatus.ACTIVE;
        subscription.currentPeriodEnd = Instant.now().plusSeconds(3600);
        return subscription;
    }

    private PlanEntitlement keywordEntitlement(String keyword, String contentType) {
        PlanEntitlement entitlement = new PlanEntitlement();
        entitlement.mode = Enums.PlanEntitlementMode.KEYWORD;
        entitlement.keyword = keyword;
        entitlement.contentType = contentType;
        entitlement.label = keyword;
        entitlement.enabled = true;
        return entitlement;
    }

    private PlanEntitlement typeEntitlement(String contentType) {
        PlanEntitlement entitlement = new PlanEntitlement();
        entitlement.mode = Enums.PlanEntitlementMode.TYPE;
        entitlement.contentType = contentType;
        entitlement.label = contentType;
        entitlement.enabled = true;
        return entitlement;
    }

    private PlanEntitlement categoryEntitlement(String categoryId) {
        PlanEntitlement entitlement = new PlanEntitlement();
        entitlement.mode = Enums.PlanEntitlementMode.CATEGORY;
        entitlement.categoryId = categoryId;
        entitlement.contentType = "all";
        entitlement.label = categoryId;
        entitlement.enabled = true;
        return entitlement;
    }

    private PlanEntitlement addonEntitlement(String addonId) {
        PlanEntitlement entitlement = new PlanEntitlement();
        entitlement.mode = Enums.PlanEntitlementMode.ADDON;
        entitlement.categoryId = addonId;
        entitlement.contentType = "all";
        entitlement.label = addonId;
        entitlement.enabled = true;
        return entitlement;
    }

    private PlanEntitlement connectorEntitlement(String connector) {
        PlanEntitlement entitlement = new PlanEntitlement();
        entitlement.mode = Enums.PlanEntitlementMode.CONNECTOR;
        entitlement.keyword = connector;
        entitlement.contentType = "all";
        entitlement.label = connector;
        entitlement.enabled = true;
        return entitlement;
    }
}
