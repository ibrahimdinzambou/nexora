package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.IptvAccount;
import com.iptv.saas.domain.Organization;
import com.iptv.saas.domain.Plan;
import com.iptv.saas.domain.Subscription;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.domain.UserSession;
import com.iptv.saas.repository.IptvAccountRepository;
import com.iptv.saas.repository.SubscriptionRepository;
import com.iptv.saas.repository.UserSessionRepository;
import com.iptv.saas.web.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamingServiceTests {
    @Test
    void rejectsAStreamOutsideTheUsersAllowedCategories() {
        UserSessionRepository sessions = mock(UserSessionRepository.class);
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        OrganizationService organizations = mock(OrganizationService.class);
        IptvCatalogService catalog = mock(IptvCatalogService.class);
        CommunityAddonService addons = mock(CommunityAddonService.class);
        SubscriptionAccessService access = mock(SubscriptionAccessService.class);
        TelegramAlertService telegram = mock(TelegramAlertService.class);
        AuditService audit = mock(AuditService.class);
        StreamingService service = new StreamingService(
                sessions, accounts, subscriptions, organizations, catalog, addons, access, telegram, audit, 90
        );

        Organization organization = new Organization();
        organization.status = Enums.OrganizationStatus.ACTIVE;
        UserEntity user = new UserEntity();
        user.allowedCategories = "[\"sports\"]";
        Plan plan = new Plan();
        Subscription subscription = new Subscription();
        subscription.plan = plan;
        subscription.status = Enums.SubscriptionStatus.ACTIVE;
        subscription.currentPeriodEnd = Instant.now().plusSeconds(3600);

        when(organizations.currentOrganization(user)).thenReturn(organization);
        when(subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(organization))
                .thenReturn(Optional.of(subscription));
        when(catalog.accessForItem("movie-1")).thenReturn(
                new IptvCatalogService.CatalogAccessDescriptor("movies-action", "Action", "movie", false)
        );

        assertThrows(ApiException.class, () -> service.open(user, "movie", "movie-1"));
        verify(catalog, times(0)).selectStream("movie", "movie-1");
    }

    @Test
    void expiresStaleSessionBeforeCheckingPlanLimit() {
        UserSessionRepository sessions = mock(UserSessionRepository.class);
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        OrganizationService organizations = mock(OrganizationService.class);
        IptvCatalogService catalog = mock(IptvCatalogService.class);
        CommunityAddonService addons = mock(CommunityAddonService.class);
        SubscriptionAccessService access = mock(SubscriptionAccessService.class);
        TelegramAlertService telegram = mock(TelegramAlertService.class);
        AuditService audit = mock(AuditService.class);
        StreamingService service = new StreamingService(
                sessions,
                accounts,
                subscriptions,
                organizations,
                catalog,
                addons,
                access,
                telegram,
                audit,
                90
        );

        Organization organization = new Organization();
        organization.id = 1L;
        organization.status = Enums.OrganizationStatus.ACTIVE;

        UserEntity user = new UserEntity();
        user.id = 2L;
        user.currentOrganization = organization;

        Plan plan = new Plan();
        plan.maxConcurrentStreams = 1;
        Subscription subscription = new Subscription();
        subscription.organization = organization;
        subscription.plan = plan;
        subscription.status = Enums.SubscriptionStatus.ACTIVE;
        subscription.currentPeriodEnd = Instant.now().plusSeconds(3600);

        IptvAccount account = new IptvAccount();
        account.id = 33L;
        account.maxStreams = 1;
        account.activeStreams = 1;

        UserSession stale = new UserSession();
        stale.id = 240L;
        stale.organization = organization;
        stale.iptvAccount = account;
        stale.status = Enums.SessionStatus.ACTIVE;
        stale.lastHeartbeatAt = Instant.now().minusSeconds(600);

        when(organizations.currentOrganization(user)).thenReturn(organization);
        when(subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(organization))
                .thenReturn(Optional.of(subscription));
        when(sessions.findByOrganizationAndStatusAndLastHeartbeatAtBefore(
                eq(organization),
                eq(Enums.SessionStatus.ACTIVE),
                any(Instant.class)
        )).thenReturn(List.of(stale));
        when(sessions.findByUserAndStatus(user, Enums.SessionStatus.ACTIVE))
                .thenAnswer(invocation -> stale.status == Enums.SessionStatus.ACTIVE ? List.of(stale) : List.of());
        when(catalog.accessForItem("episode-1")).thenReturn(
                new IptvCatalogService.CatalogAccessDescriptor("series-drama", "Drama", "series", false)
        );
        when(access.permits(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(true);
        when(catalog.selectStream("series", "episode-1")).thenReturn(
                new IptvCatalogService.StreamSelection(account, "https://example.test/episode-1")
        );
        when(catalog.health(account)).thenReturn("ok");
        when(sessions.save(any(UserSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserSession opened = service.open(user, "series", "episode-1");

        assertEquals(Enums.SessionStatus.EXPIRED, stale.status);
        assertNotNull(stale.closedAt);
        assertEquals(Enums.SessionStatus.ACTIVE, opened.status);
        assertEquals(1, account.activeStreams);
        verify(accounts, times(2)).save(account);
        verify(catalog).selectStream("series", "episode-1");
    }

    @Test
    void allowsTwoDifferentUsersToStreamWithAOneStreamPerUserPlan() {
        UserSessionRepository sessions = mock(UserSessionRepository.class);
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        OrganizationService organizations = mock(OrganizationService.class);
        IptvCatalogService catalog = mock(IptvCatalogService.class);
        CommunityAddonService addons = mock(CommunityAddonService.class);
        SubscriptionAccessService access = mock(SubscriptionAccessService.class);
        TelegramAlertService telegram = mock(TelegramAlertService.class);
        AuditService audit = mock(AuditService.class);
        StreamingService service = new StreamingService(
                sessions,
                accounts,
                subscriptions,
                organizations,
                catalog,
                addons,
                access,
                telegram,
                audit,
                90
        );

        Organization organization = new Organization();
        organization.id = 1L;
        organization.status = Enums.OrganizationStatus.ACTIVE;

        UserEntity firstUser = new UserEntity();
        firstUser.id = 10L;
        firstUser.currentOrganization = organization;
        UserEntity secondUser = new UserEntity();
        secondUser.id = 20L;
        secondUser.currentOrganization = organization;

        Plan plan = new Plan();
        plan.maxConcurrentStreams = 1;
        Subscription subscription = new Subscription();
        subscription.organization = organization;
        subscription.plan = plan;
        subscription.status = Enums.SubscriptionStatus.ACTIVE;
        subscription.currentPeriodEnd = Instant.now().plusSeconds(3600);

        IptvAccount firstAccount = new IptvAccount();
        firstAccount.id = 33L;
        firstAccount.maxStreams = 1;
        IptvAccount secondAccount = new IptvAccount();
        secondAccount.id = 65L;
        secondAccount.maxStreams = 1;

        UserSession firstActiveSession = new UserSession();
        firstActiveSession.user = firstUser;
        firstActiveSession.organization = organization;
        firstActiveSession.iptvAccount = firstAccount;
        firstActiveSession.status = Enums.SessionStatus.ACTIVE;

        when(organizations.currentOrganization(secondUser)).thenReturn(organization);
        when(subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(organization))
                .thenReturn(Optional.of(subscription));
        when(sessions.findByOrganizationAndStatusAndLastHeartbeatAtBefore(
                eq(organization),
                eq(Enums.SessionStatus.ACTIVE),
                any(Instant.class)
        )).thenReturn(List.of());
        when(sessions.findByUserAndStatus(secondUser, Enums.SessionStatus.ACTIVE)).thenReturn(List.of());
        when(sessions.findByOrganizationAndStatus(organization, Enums.SessionStatus.ACTIVE))
                .thenReturn(List.of(firstActiveSession));
        when(catalog.accessForItem("channel-2")).thenReturn(
                new IptvCatalogService.CatalogAccessDescriptor("live-general", "General", "live", false)
        );
        when(access.permits(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(true);
        when(catalog.selectStream("live", "channel-2")).thenReturn(
                new IptvCatalogService.StreamSelection(secondAccount, "https://second.test/live.ts")
        );
        when(catalog.health(secondAccount)).thenReturn("ok");
        when(sessions.save(any(UserSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserSession opened = service.open(secondUser, "live", "channel-2");

        assertEquals(secondUser, opened.user);
        assertEquals(secondAccount, opened.iptvAccount);
        assertEquals(1, secondAccount.activeStreams);
        verify(sessions).findByUserAndStatus(secondUser, Enums.SessionStatus.ACTIVE);
    }

    @Test
    void replacesPreviousStreamWhenSingleStreamUserChangesContent() {
        UserSessionRepository sessions = mock(UserSessionRepository.class);
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        OrganizationService organizations = mock(OrganizationService.class);
        IptvCatalogService catalog = mock(IptvCatalogService.class);
        CommunityAddonService addons = mock(CommunityAddonService.class);
        SubscriptionAccessService access = mock(SubscriptionAccessService.class);
        TelegramAlertService telegram = mock(TelegramAlertService.class);
        AuditService audit = mock(AuditService.class);
        StreamingService service = new StreamingService(
                sessions, accounts, subscriptions, organizations, catalog, addons, access, telegram, audit, 90
        );

        Organization organization = new Organization();
        organization.status = Enums.OrganizationStatus.ACTIVE;
        UserEntity user = new UserEntity();
        user.id = 2L;
        Plan plan = new Plan();
        plan.maxConcurrentStreams = 1;
        Subscription subscription = new Subscription();
        subscription.plan = plan;
        subscription.status = Enums.SubscriptionStatus.ACTIVE;
        subscription.currentPeriodEnd = Instant.now().plusSeconds(3600);

        IptvAccount oldAccount = new IptvAccount();
        oldAccount.id = 33L;
        oldAccount.activeStreams = 1;
        oldAccount.maxStreams = 1;
        IptvAccount newAccount = new IptvAccount();
        newAccount.id = 65L;
        newAccount.maxStreams = 1;

        UserSession previous = new UserSession();
        previous.user = user;
        previous.organization = organization;
        previous.iptvAccount = oldAccount;
        previous.contentType = "live";
        previous.itemId = "channel-1";
        previous.status = Enums.SessionStatus.ACTIVE;

        when(organizations.currentOrganization(user)).thenReturn(organization);
        when(subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(organization))
                .thenReturn(Optional.of(subscription));
        when(sessions.findByOrganizationAndStatusAndLastHeartbeatAtBefore(
                eq(organization), eq(Enums.SessionStatus.ACTIVE), any(Instant.class)
        )).thenReturn(List.of());
        when(sessions.findByUserAndStatus(user, Enums.SessionStatus.ACTIVE)).thenReturn(List.of(previous));
        when(catalog.accessForItem("channel-2")).thenReturn(
                new IptvCatalogService.CatalogAccessDescriptor("live-general", "General", "live", false)
        );
        when(access.permits(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(true);
        when(catalog.selectStream("live", "channel-2")).thenReturn(
                new IptvCatalogService.StreamSelection(newAccount, "https://new.test/live.ts")
        );
        when(catalog.health(oldAccount)).thenReturn("ok");
        when(catalog.health(newAccount)).thenReturn("ok");
        when(sessions.save(any(UserSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserSession opened = service.open(user, "live", "channel-2");

        assertEquals(Enums.SessionStatus.CLOSED, previous.status);
        assertEquals(0, oldAccount.activeStreams);
        assertEquals(newAccount, opened.iptvAccount);
        assertEquals(1, newAccount.activeStreams);
    }

    @Test
    void reusesExistingSessionForDuplicateOpenRequest() {
        UserSessionRepository sessions = mock(UserSessionRepository.class);
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        OrganizationService organizations = mock(OrganizationService.class);
        IptvCatalogService catalog = mock(IptvCatalogService.class);
        CommunityAddonService addons = mock(CommunityAddonService.class);
        SubscriptionAccessService access = mock(SubscriptionAccessService.class);
        TelegramAlertService telegram = mock(TelegramAlertService.class);
        AuditService audit = mock(AuditService.class);
        StreamingService service = new StreamingService(
                sessions, accounts, subscriptions, organizations, catalog, addons, access, telegram, audit, 90
        );

        Organization organization = new Organization();
        organization.status = Enums.OrganizationStatus.ACTIVE;
        UserEntity user = new UserEntity();
        Plan plan = new Plan();
        plan.maxConcurrentStreams = 1;
        Subscription subscription = new Subscription();
        subscription.plan = plan;
        subscription.status = Enums.SubscriptionStatus.ACTIVE;
        subscription.currentPeriodEnd = Instant.now().plusSeconds(3600);
        UserSession existing = new UserSession();
        existing.user = user;
        existing.contentType = "series";
        existing.itemId = "episode-1";
        existing.status = Enums.SessionStatus.ACTIVE;

        when(organizations.currentOrganization(user)).thenReturn(organization);
        when(subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(organization))
                .thenReturn(Optional.of(subscription));
        when(sessions.findByOrganizationAndStatusAndLastHeartbeatAtBefore(
                eq(organization), eq(Enums.SessionStatus.ACTIVE), any(Instant.class)
        )).thenReturn(List.of());
        when(sessions.findByUserAndStatus(user, Enums.SessionStatus.ACTIVE)).thenReturn(List.of(existing));
        when(sessions.save(existing)).thenReturn(existing);
        when(catalog.accessForItem("episode-1")).thenReturn(
                new IptvCatalogService.CatalogAccessDescriptor("series-drama", "Drama", "series", false)
        );
        when(access.permits(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(true);

        UserSession reused = service.open(user, "series", "episode-1", "fullhd");

        assertEquals(existing, reused);
        assertNotNull(reused.lastHeartbeatAt);
        assertEquals("fullhd", reused.playbackQuality);
        verify(catalog, times(0)).selectStream("series", "episode-1");
    }

    @Test
    void changesQualityOnTheExistingActiveSession() {
        UserSessionRepository sessions = mock(UserSessionRepository.class);
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        OrganizationService organizations = mock(OrganizationService.class);
        IptvCatalogService catalog = mock(IptvCatalogService.class);
        CommunityAddonService addons = mock(CommunityAddonService.class);
        SubscriptionAccessService access = mock(SubscriptionAccessService.class);
        TelegramAlertService telegram = mock(TelegramAlertService.class);
        AuditService audit = mock(AuditService.class);
        StreamingService service = new StreamingService(
                sessions, accounts, subscriptions, organizations, catalog, addons, access, telegram, audit, 90
        );

        UserEntity user = new UserEntity();
        user.id = 2L;
        UserSession session = new UserSession();
        session.user = user;
        session.sessionToken = "token";
        session.status = Enums.SessionStatus.ACTIVE;
        session.playbackQuality = "auto";

        when(sessions.findBySessionToken("token")).thenReturn(Optional.of(session));
        when(sessions.save(session)).thenReturn(session);

        UserSession changed = service.changeQuality(user, "token", "hd");

        assertEquals(session, changed);
        assertEquals("hd", changed.playbackQuality);
        assertNotNull(changed.lastHeartbeatAt);
        verify(catalog, times(0)).selectStream("live", "token");
    }

    @Test
    void movesAnActiveSessionToAnotherAccountAfterAStreamFailure() {
        UserSessionRepository sessions = mock(UserSessionRepository.class);
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        OrganizationService organizations = mock(OrganizationService.class);
        IptvCatalogService catalog = mock(IptvCatalogService.class);
        CommunityAddonService addons = mock(CommunityAddonService.class);
        SubscriptionAccessService access = mock(SubscriptionAccessService.class);
        TelegramAlertService telegram = mock(TelegramAlertService.class);
        AuditService audit = mock(AuditService.class);
        StreamingService service = new StreamingService(
                sessions,
                accounts,
                subscriptions,
                organizations,
                catalog,
                addons,
                access,
                telegram,
                audit,
                90
        );

        IptvAccount failed = new IptvAccount();
        failed.id = 33L;
        failed.activeStreams = 1;
        IptvAccount replacement = new IptvAccount();
        replacement.id = 65L;
        replacement.maxStreams = 2;

        UserSession session = new UserSession();
        session.id = 9L;
        session.status = Enums.SessionStatus.ACTIVE;
        session.iptvAccount = failed;
        session.contentType = "live";
        session.itemId = "shared-channel";
        session.streamUrl = "https://failed.test/live.ts";

        when(catalog.selectStream("live", "shared-channel", Set.of(failed.id))).thenReturn(
                new IptvCatalogService.StreamSelection(replacement, "https://replacement.test/live.ts")
        );
        when(catalog.health(replacement)).thenReturn("ok");
        when(sessions.save(session)).thenReturn(session);

        UserSession moved = service.failover(session);

        assertEquals(0, failed.activeStreams);
        assertEquals("stream-failed", failed.lastHealthStatus);
        assertEquals(1, replacement.activeStreams);
        assertEquals(replacement, moved.iptvAccount);
        assertEquals("https://replacement.test/live.ts", moved.streamUrl);
        verify(accounts).save(failed);
        verify(accounts).save(replacement);
    }

    @Test
    void marksTheFailedAccountEvenWhenNoFailoverSourceExists() {
        UserSessionRepository sessions = mock(UserSessionRepository.class);
        IptvAccountRepository accounts = mock(IptvAccountRepository.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        OrganizationService organizations = mock(OrganizationService.class);
        IptvCatalogService catalog = mock(IptvCatalogService.class);
        CommunityAddonService addons = mock(CommunityAddonService.class);
        SubscriptionAccessService access = mock(SubscriptionAccessService.class);
        TelegramAlertService telegram = mock(TelegramAlertService.class);
        AuditService audit = mock(AuditService.class);
        StreamingService service = new StreamingService(
                sessions,
                accounts,
                subscriptions,
                organizations,
                catalog,
                addons,
                access,
                telegram,
                audit,
                90
        );

        IptvAccount failed = new IptvAccount();
        failed.id = 33L;
        failed.activeStreams = 1;

        UserSession session = new UserSession();
        session.id = 9L;
        session.status = Enums.SessionStatus.ACTIVE;
        session.iptvAccount = failed;
        session.contentType = "live";
        session.itemId = "unique-channel";
        session.streamUrl = "https://failed.test/live.ts";

        ApiException unavailable = ApiException.serviceUnavailable("Aucun compte IPTV ne peut servir ce contenu");
        when(catalog.selectStream("live", "unique-channel", Set.of(failed.id))).thenThrow(unavailable);

        ApiException thrown = assertThrows(ApiException.class, () -> service.failover(session));

        assertEquals(unavailable, thrown);
        assertEquals(0, failed.activeStreams);
        assertEquals(1, failed.failureCount);
        assertEquals("stream-failed", failed.lastHealthStatus);
        verify(accounts).save(failed);
    }
}
