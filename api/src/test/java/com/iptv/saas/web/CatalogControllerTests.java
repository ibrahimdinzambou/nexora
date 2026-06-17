package com.iptv.saas.web;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.SubscriptionRepository;
import com.iptv.saas.service.CommunityAddonService;
import com.iptv.saas.service.IptvCatalogService;
import com.iptv.saas.service.OrganizationService;
import com.iptv.saas.service.SubscriptionAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogControllerTests {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void selectedUserCanSeeAnAdultPrivateAddonCategory() {
        IptvCatalogService catalog = mock(IptvCatalogService.class);
        CommunityAddonService addons = mock(CommunityAddonService.class);
        SubscriptionAccessService access = new SubscriptionAccessService(
                mock(SubscriptionRepository.class),
                mock(OrganizationService.class)
        );
        CatalogController controller = new CatalogController(catalog, addons, access);
        UserEntity user = new UserEntity();
        user.id = 55L;
        user.role = Enums.UserRole.USER;

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of())
        );
        when(catalog.hasActiveSources()).thenReturn(false);
        when(addons.hasApprovedAddons(user)).thenReturn(true);
        when(addons.categories(null, user)).thenReturn(List.of(Map.of(
                "id", "addon-1-movie-asa",
                "name", "ASA",
                "adult", true,
                "privateUse", true,
                "privateAccess", true
        )));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) controller.categories(null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> values = (List<Map<String, Object>>) response.get("data");

        assertEquals(1, values.size());
        assertEquals("addon-1-movie-asa", values.get(0).get("id"));
    }
}
