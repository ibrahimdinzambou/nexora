package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.Organization;
import com.iptv.saas.domain.PlanEntitlement;
import com.iptv.saas.domain.Subscription;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.SubscriptionRepository;
import com.iptv.saas.security.CatalogCategoryAccess;
import com.iptv.saas.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SubscriptionAccessService {
    private final SubscriptionRepository subscriptions;
    private final OrganizationService organizationService;

    public SubscriptionAccessService(SubscriptionRepository subscriptions, OrganizationService organizationService) {
        this.subscriptions = subscriptions;
        this.organizationService = organizationService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> filter(UserEntity user, List<Map<String, Object>> values) {
        if (user == null) {
            return CatalogCategoryAccess.filter(null, values);
        }
        Subscription subscription = currentUsableSubscription(user);
        return values.stream()
                .filter(value -> hasPrivateAccess(value) || permits(user, subscription, value))
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean permits(UserEntity user, Map<String, Object> value) {
        if (user == null) {
            return permitsWithoutSubscription(null, value);
        }
        return hasPrivateAccess(value) || permits(user, currentUsableSubscription(user), value);
    }

    public boolean permits(
            UserEntity user,
            Subscription subscription,
            String contentType,
            String categoryId,
            String categoryName,
            boolean adult
    ) {
        if (user == null) {
            return adult
                    ? CatalogCategoryAccess.permitsExplicitly(null, categoryId)
                    : CatalogCategoryAccess.permits(null, categoryId);
        }
        if (!isUsable(subscription)) {
            return false;
        }

        Set<String> manualCategories = CatalogCategoryAccess.allowedCategoryIds(user);
        boolean userAllows = manualCategories.isEmpty()
                || (categoryId != null && manualCategories.contains(categoryId));
        if (!userAllows) {
            return false;
        }

        List<PlanEntitlement> entitlements = enabledEntitlements(subscription);
        if (entitlements.isEmpty()) {
            return adult
                    ? categoryId != null && manualCategories.contains(categoryId)
                    : true;
        }

        return entitlements.stream().anyMatch(entitlement -> entitlementMatches(
                entitlement,
                contentType,
                categoryId,
                categoryName,
                adult
        ));
    }

    public boolean isUsable(Subscription subscription) {
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

    private Subscription currentUsableSubscription(UserEntity user) {
        Organization organization;
        try {
            organization = organizationService.currentOrganization(user);
        } catch (ApiException exception) {
            return null;
        }
        if (organization == null || organization.status != Enums.OrganizationStatus.ACTIVE) {
            return null;
        }
        return subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(organization).orElse(null);
    }

    private boolean permits(UserEntity user, Subscription subscription, Map<String, Object> value) {
        return permits(
                user,
                subscription,
                stringValue(value.get("type")),
                categoryId(value),
                categoryName(value),
                Boolean.TRUE.equals(value.get("adult"))
        );
    }

    private boolean permitsWithoutSubscription(UserEntity user, Map<String, Object> value) {
        String categoryId = categoryId(value);
        return Boolean.TRUE.equals(value.get("adult"))
                ? CatalogCategoryAccess.permitsExplicitly(user, categoryId)
                : CatalogCategoryAccess.permits(user, categoryId);
    }

    private boolean hasPrivateAccess(Map<String, Object> value) {
        return Boolean.TRUE.equals(value.get("privateUse"))
                && Boolean.TRUE.equals(value.get("privateAccess"));
    }

    private List<PlanEntitlement> enabledEntitlements(Subscription subscription) {
        if (subscription == null || subscription.plan == null || subscription.plan.entitlements == null) {
            return List.of();
        }
        return subscription.plan.entitlements.stream()
                .filter(entitlement -> entitlement != null && entitlement.enabled)
                .toList();
    }

    private boolean entitlementMatches(
            PlanEntitlement entitlement,
            String contentType,
            String categoryId,
            String categoryName,
            boolean adult
    ) {
        if (!typeMatches(entitlement.contentType, contentType)) {
            return false;
        }
        Enums.PlanEntitlementMode mode = entitlement.mode == null
                ? Enums.PlanEntitlementMode.ALL
                : entitlement.mode;
        if (adult && mode != Enums.PlanEntitlementMode.CATEGORY && mode != Enums.PlanEntitlementMode.ADDON) {
            return false;
        }
        return switch (mode) {
            case ALL -> true;
            case TYPE -> true;
            case CATEGORY -> categoryId != null && categoryId.equals(entitlement.categoryId);
            case KEYWORD -> keywordMatches(entitlement.keyword, categoryId, categoryName, contentType);
            case ADDON -> addonMatches(entitlement.categoryId, categoryId);
            case CONNECTOR -> false;
        };
    }

    private boolean typeMatches(String entitlementType, String contentType) {
        String entitlement = normalizeType(entitlementType);
        String requested = normalizeType(contentType);
        return "all".equals(entitlement) || requested.equals(entitlement);
    }

    private boolean keywordMatches(String keyword, String categoryId, String categoryName, String contentType) {
        String normalizedKeyword = normalize(keyword);
        if (normalizedKeyword.isBlank()) {
            return false;
        }
        return normalize(categoryId).contains(normalizedKeyword)
                || normalize(categoryName).contains(normalizedKeyword)
                || normalize(contentType).contains(normalizedKeyword);
    }

    private boolean addonMatches(String entitlementAddonId, String categoryId) {
        if (entitlementAddonId == null || entitlementAddonId.isBlank() || categoryId == null || categoryId.isBlank()) {
            return false;
        }
        String value = entitlementAddonId.trim();
        if (categoryId.equals(value) || categoryId.startsWith(value + "-")) {
            return true;
        }
        if (value.startsWith("addon:")) {
            String id = value.substring("addon:".length()).trim();
            return !id.isBlank() && categoryId.startsWith("addon-" + id + "-");
        }
        return false;
    }

    private String categoryId(Map<String, Object> value) {
        Object explicit = value.get("categoryId");
        if (explicit != null) {
            return String.valueOf(explicit);
        }
        Object id = value.get("id");
        return id == null ? null : String.valueOf(id);
    }

    private String categoryName(Map<String, Object> value) {
        Object name = value.get("categoryName");
        if (name == null) {
            name = value.get("name");
        }
        return stringValue(name);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String normalizeType(String type) {
        String value = normalize(type);
        return value.isBlank() ? "all" : value;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }
}
