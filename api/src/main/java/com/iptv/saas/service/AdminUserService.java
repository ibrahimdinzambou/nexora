package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.AuthTokenRepository;
import com.iptv.saas.repository.IptvAccountRepository;
import com.iptv.saas.repository.OrganizationMembershipRepository;
import com.iptv.saas.repository.UserRepository;
import com.iptv.saas.repository.UserSessionRepository;
import com.iptv.saas.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class AdminUserService {
    private final UserRepository users;
    private final AuthTokenRepository tokens;
    private final UserSessionRepository sessions;
    private final IptvAccountRepository accounts;
    private final OrganizationMembershipRepository memberships;
    private final AuditService audit;

    public AdminUserService(
            UserRepository users,
            AuthTokenRepository tokens,
            UserSessionRepository sessions,
            IptvAccountRepository accounts,
            OrganizationMembershipRepository memberships,
            AuditService audit
    ) {
        this.users = users;
        this.tokens = tokens;
        this.sessions = sessions;
        this.accounts = accounts;
        this.memberships = memberships;
        this.audit = audit;
    }

    @Transactional
    public UserEntity setActive(UserEntity actor, Long userId, Boolean active) {
        UserEntity user = find(userId);
        boolean next = active == null ? !user.active : active;
        if (!next) {
            guardSelf(actor, user, "Impossible de desactiver votre propre compte");
            guardLastSuperAdmin(user);
        }
        user.active = next;
        if (!next) {
            revokeAccess(user);
        }
        user = users.save(user);
        audit.log(actor, next ? "admin.user.activated" : "admin.user.deactivated", "User", user.id, user.email);
        return user;
    }

    @Transactional
    public UserEntity setRole(UserEntity actor, Long userId, Enums.UserRole role) {
        if (role == null) {
            throw ApiException.validation("Role invalide");
        }
        UserEntity user = find(userId);
        guardSelf(actor, user, "Impossible de changer votre propre role");
        if (user.role == Enums.UserRole.SUPER_ADMIN && role != Enums.UserRole.SUPER_ADMIN) {
            guardLastSuperAdmin(user);
        }
        user.role = role;
        user = users.save(user);
        audit.log(actor, "admin.user.role", "User", user.id, role.name());
        return user;
    }

    @Transactional
    public UserEntity deleteUser(UserEntity actor, Long userId) {
        UserEntity user = find(userId);
        guardSelf(actor, user, "Impossible de supprimer votre propre compte");
        guardLastSuperAdmin(user);
        revokeAccess(user);
        memberships.deleteByUser(user);
        user.active = false;
        user.role = Enums.UserRole.USER;
        user.emailVerified = false;
        user.twoFactorEnabled = false;
        user.emailOtp = null;
        user.emailOtpExpiresAt = null;
        user.resetOtp = null;
        user.resetOtpExpiresAt = null;
        user.twoFactorCode = null;
        user.twoFactorCodeExpiresAt = null;
        user.allowedCategories = "[]";
        user.currentOrganization = null;
        user.name = "Utilisateur supprime #" + user.id;
        user.email = "deleted+" + user.id + "@nexora.local";
        user.passwordHash = "deleted-" + UUID.randomUUID();
        user = users.save(user);
        audit.log(actor, "admin.user.deleted", "User", user.id, user.email);
        return user;
    }

    private UserEntity find(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
    }

    private void revokeAccess(UserEntity user) {
        tokens.deleteByUser(user);
        Instant now = Instant.now();
        sessions.findByUserAndStatus(user, Enums.SessionStatus.ACTIVE).forEach(session -> {
            session.status = Enums.SessionStatus.CLOSED;
            session.closedAt = now;
            if (session.iptvAccount != null && session.iptvAccount.activeStreams > 0) {
                session.iptvAccount.activeStreams -= 1;
                accounts.save(session.iptvAccount);
            }
            sessions.save(session);
        });
    }

    private void guardSelf(UserEntity actor, UserEntity user, String message) {
        if (actor != null && actor.id != null && user.id != null && Objects.equals(actor.id, user.id)) {
            throw ApiException.validation(message);
        }
    }

    private void guardLastSuperAdmin(UserEntity user) {
        if (user.role == Enums.UserRole.SUPER_ADMIN
                && user.active
                && users.countByRoleAndActiveTrue(Enums.UserRole.SUPER_ADMIN) <= 1) {
            throw ApiException.validation("Conservez au moins un super administrateur actif");
        }
    }
}
