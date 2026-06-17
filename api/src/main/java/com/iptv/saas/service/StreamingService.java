package com.iptv.saas.service;

import com.iptv.saas.domain.*;
import com.iptv.saas.repository.IptvAccountRepository;
import com.iptv.saas.repository.SubscriptionRepository;
import com.iptv.saas.repository.UserSessionRepository;
import com.iptv.saas.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class StreamingService {
    private final UserSessionRepository sessions;
    private final IptvAccountRepository accounts;
    private final SubscriptionRepository subscriptions;
    private final OrganizationService organizationService;
    private final IptvCatalogService catalogService;
    private final CommunityAddonService addons;
    private final SubscriptionAccessService access;
    private final TelegramAlertService telegram;
    private final AuditService audit;
    private final int heartbeatTimeoutSeconds;

    public StreamingService(
            UserSessionRepository sessions,
            IptvAccountRepository accounts,
            SubscriptionRepository subscriptions,
            OrganizationService organizationService,
            IptvCatalogService catalogService,
            CommunityAddonService addons,
            SubscriptionAccessService access,
            TelegramAlertService telegram,
            AuditService audit,
            @Value("${app.iptv.heartbeat-timeout-seconds:90}") int heartbeatTimeoutSeconds
    ) {
        this.sessions = sessions;
        this.accounts = accounts;
        this.subscriptions = subscriptions;
        this.organizationService = organizationService;
        this.catalogService = catalogService;
        this.addons = addons;
        this.access = access;
        this.telegram = telegram;
        this.audit = audit;
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
    }

    @Transactional
    public synchronized UserSession open(UserEntity user, String type, String itemId) {
        return open(user, type, itemId, "auto");
    }

    @Transactional
    public synchronized UserSession open(UserEntity user, String type, String itemId, String quality) {
        Organization organization = organizationService.currentOrganization(user);
        Subscription subscription = requireActiveSubscription(organization);
        String normalizedQuality = VlcRemuxService.normalizeQuality(quality);
        String normalizedType = type == null || type.isBlank() ? "live" : type;
        boolean addonItem = addons.isAddonItem(itemId);
        String categoryId;
        String categoryName;
        String accessType;
        boolean adult;
        if (addonItem) {
            categoryId = addons.categoryIdForItem(itemId, user);
            categoryName = null;
            accessType = normalizedType;
            adult = addons.isAdultItem(itemId, user);
        } else {
            IptvCatalogService.CatalogAccessDescriptor descriptor = catalogService.accessForItem(itemId);
            categoryId = descriptor.categoryId();
            categoryName = descriptor.categoryName();
            accessType = descriptor.contentType() == null ? normalizedType : descriptor.contentType();
            adult = descriptor.adult();
        }
        boolean privateAccess = addonItem && addons.hasPrivateAccess(itemId, user);
        boolean permitted = privateAccess || access.permits(user, subscription, accessType, categoryId, categoryName, adult);
        if (!permitted) {
            throw ApiException.forbidden("Cette catégorie n'est pas autorisée pour votre compte");
        }
        cleanupInactive(organization);
        List<UserSession> activeForUser = sessions.findByUserAndStatus(user, Enums.SessionStatus.ACTIVE);
        UserSession existing = activeForUser.stream()
                .filter(session -> normalizedType.equals(session.contentType))
                .filter(session -> itemId.equals(session.itemId))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            existing.lastHeartbeatAt = Instant.now();
            existing.playbackQuality = normalizedQuality;
            return sessions.save(existing);
        }

        int maxStreams = subscription.plan == null ? 1 : subscription.plan.maxConcurrentStreams;
        if (activeForUser.size() >= maxStreams && maxStreams == 1) {
            activeForUser.forEach(session -> closeSession(session, Enums.SessionStatus.CLOSED));
            activeForUser = List.of();
        }
        if (activeForUser.size() >= maxStreams) {
            throw ApiException.paymentRequired(
                    "Limite de streams simultanés atteinte pour cet utilisateur (" + maxStreams + ")"
            );
        }

        IptvAccount account = null;
        String streamUrl;
        if (addons.isAddonItem(itemId)) {
            streamUrl = addons.streamUrl(itemId, user);
        } else {
            IptvCatalogService.StreamSelection selection = catalogService.selectStream(type, itemId);
            account = selection.account();
            streamUrl = selection.streamUrl();
            account.activeStreams += 1;
            account.lastHealthStatus = catalogService.health(account);
            accounts.save(account);
        }

        UserSession session = new UserSession();
        session.sessionToken = UUID.randomUUID().toString();
        session.user = user;
        session.organization = organization;
        session.iptvAccount = account;
        session.contentType = normalizedType;
        session.itemId = itemId;
        session.streamUrl = streamUrl;
        session.playbackQuality = normalizedQuality;
        session.status = Enums.SessionStatus.ACTIVE;
        session.openedAt = Instant.now();
        session.lastHeartbeatAt = Instant.now();
        session = sessions.save(session);
        audit.log(user, "stream.opened", "UserSession", session.id, session.contentType + ":" + itemId);
        return session;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public synchronized UserSession failover(UserSession session) {
        return failover(session, Set.of());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public synchronized UserSession failover(UserSession session, Set<Long> excludedAccountIds) {
        if (session == null || session.status != Enums.SessionStatus.ACTIVE || session.iptvAccount == null) {
            throw ApiException.validation("Session inactive, bascule impossible");
        }

        IptvAccount failedAccount = session.iptvAccount;
        Set<Long> excluded = new LinkedHashSet<>(excludedAccountIds == null ? Set.of() : excludedAccountIds);
        if (failedAccount.id != null) {
            excluded.add(failedAccount.id);
        }
        markStreamFailed(failedAccount);
        IptvCatalogService.StreamSelection selection = catalogService.selectStream(
                session.contentType,
                session.itemId,
                excluded
        );
        IptvAccount replacement = selection.account();

        replacement.activeStreams += 1;
        replacement.lastHealthStatus = catalogService.health(replacement);
        accounts.save(replacement);

        session.iptvAccount = replacement;
        session.streamUrl = selection.streamUrl();
        session.lastHeartbeatAt = Instant.now();
        session = sessions.save(session);
        telegram.send(
                "Bascule IPTV automatique",
                "Session #" + session.id + ": compte " + failedAccount.id + " vers " + replacement.id
        );
        audit.log(
                session.user,
                "stream.failover",
                "UserSession",
                session.id,
                failedAccount.id + "->" + replacement.id
        );
        return session;
    }

    private void markStreamFailed(IptvAccount account) {
        if (account.activeStreams > 0) {
            account.activeStreams -= 1;
        }
        account.failureCount += 1;
        account.lastHealthStatus = "stream-failed";
        accounts.save(account);
    }

    @Transactional
    public synchronized UserSession changeQuality(UserEntity user, String sessionToken, String quality) {
        UserSession session = getActiveByToken(sessionToken);
        if (!sameUser(session.user, user)) {
            throw ApiException.forbidden("Cette session ne vous appartient pas");
        }

        session.playbackQuality = VlcRemuxService.normalizeQuality(quality);
        session.lastHeartbeatAt = Instant.now();
        return sessions.save(session);
    }

    @Transactional(readOnly = true)
    public UserSession getActiveByToken(String sessionToken) {
        UserSession session = sessions.findBySessionToken(sessionToken)
                .orElseThrow(() -> ApiException.notFound("Session introuvable"));
        if (session.status != Enums.SessionStatus.ACTIVE) {
            throw ApiException.validation("Session fermee ou expiree");
        }
        return session;
    }

    @Transactional
    public UserSession heartbeat(String sessionToken) {
        UserSession session = getActiveByToken(sessionToken);
        session.lastHeartbeatAt = Instant.now();
        return sessions.save(session);
    }

    @Transactional
    public synchronized UserSession close(UserEntity actor, String sessionToken) {
        UserSession session = sessions.findBySessionToken(sessionToken)
                .orElseThrow(() -> ApiException.notFound("Session introuvable"));
        if (session.status == Enums.SessionStatus.ACTIVE) {
            closeSession(session, Enums.SessionStatus.CLOSED);
            audit.log(actor, "stream.closed", "UserSession", session.id, sessionToken);
        }
        return session;
    }

    @Transactional
    public synchronized void closeByAdmin(UserEntity admin, Long sessionId) {
        UserSession session = sessions.findById(sessionId).orElseThrow(() -> ApiException.notFound("Session introuvable"));
        if (session.status == Enums.SessionStatus.ACTIVE) {
            closeSession(session, Enums.SessionStatus.CLOSED);
            audit.log(admin, "admin.stream.closed", "UserSession", session.id, session.sessionToken);
        }
    }

    @Transactional
    public synchronized int cleanupInactive() {
        Instant threshold = Instant.now().minus(heartbeatTimeoutSeconds, ChronoUnit.SECONDS);
        List<UserSession> stale = sessions.findByStatusAndLastHeartbeatAtBefore(Enums.SessionStatus.ACTIVE, threshold);
        return expireSessions(stale);
    }

    private int cleanupInactive(Organization organization) {
        Instant threshold = Instant.now().minus(heartbeatTimeoutSeconds, ChronoUnit.SECONDS);
        List<UserSession> stale = sessions.findByOrganizationAndStatusAndLastHeartbeatAtBefore(
                organization,
                Enums.SessionStatus.ACTIVE,
                threshold
        );
        return expireSessions(stale);
    }

    private int expireSessions(List<UserSession> stale) {
        stale.forEach(session -> closeSession(session, Enums.SessionStatus.EXPIRED));
        return stale.size();
    }

    private Subscription requireActiveSubscription(Organization organization) {
        if (organization.status != Enums.OrganizationStatus.ACTIVE) {
            throw ApiException.paymentRequired("Organisation suspendue");
        }
        Subscription subscription = subscriptions.findFirstByOrganizationOrderByCreatedAtDesc(organization)
                .orElseThrow(() -> ApiException.paymentRequired("Abonnement requis"));
        boolean validStatus = subscription.status == Enums.SubscriptionStatus.ACTIVE
                || subscription.status == Enums.SubscriptionStatus.TRIALING;
        Instant end = subscription.status == Enums.SubscriptionStatus.TRIALING
                ? subscription.trialEndsAt
                : subscription.currentPeriodEnd;
        if (!validStatus || (end != null && end.isBefore(Instant.now()))) {
            throw ApiException.paymentRequired("Abonnement inactif ou expire");
        }
        return subscription;
    }

    private void closeSession(UserSession session, Enums.SessionStatus status) {
        session.status = status;
        session.closedAt = Instant.now();
        if (session.iptvAccount != null && session.iptvAccount.activeStreams > 0) {
            session.iptvAccount.activeStreams -= 1;
            session.iptvAccount.lastHealthStatus = catalogService.health(session.iptvAccount);
            accounts.save(session.iptvAccount);
        }
        sessions.save(session);
    }

    private boolean sameUser(UserEntity left, UserEntity right) {
        return left != null
                && right != null
                && left.id != null
                && Objects.equals(left.id, right.id);
    }
}
