package com.iptv.saas.service;

import com.iptv.saas.domain.Enums;
import com.iptv.saas.domain.Organization;
import com.iptv.saas.domain.Subscription;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.UserRepository;
import com.iptv.saas.web.ApiException;
import com.iptv.saas.web.ApiMappers;
import com.iptv.saas.web.Responses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final OrganizationService organizationService;
    private final BillingService billingService;
    private final TransactionalMailService mail;
    private final EmailTemplateService templates;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();
    private final boolean requireEmailVerification;
    private final long emailOtpTtlMinutes;
    private final long twoFactorTtlMinutes;

    public AuthService(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            TokenService tokenService,
            OrganizationService organizationService,
            BillingService billingService,
            TransactionalMailService mail,
            EmailTemplateService templates,
            AuditService audit,
            @Value("${app.security.require-email-verification:true}") boolean requireEmailVerification,
            @Value("${app.security.email-otp-ttl-minutes:15}") long emailOtpTtlMinutes,
            @Value("${app.security.two-factor-code-ttl-minutes:10}") long twoFactorTtlMinutes
    ) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.organizationService = organizationService;
        this.billingService = billingService;
        this.mail = mail;
        this.templates = templates;
        this.audit = audit;
        this.requireEmailVerification = requireEmailVerification;
        this.emailOtpTtlMinutes = emailOtpTtlMinutes;
        this.twoFactorTtlMinutes = twoFactorTtlMinutes;
    }

    @Transactional
    public Map<String, Object> register(
            String name,
            String email,
            String password,
            String organizationName,
            String planCode
    ) {
        String normalizedEmail = normalizeEmail(email);
        if (users.existsByEmailIgnoreCase(normalizedEmail)) {
            throw ApiException.validation("Cet email est deja utilise");
        }
        UserEntity user = new UserEntity();
        user.name = name;
        user.email = normalizedEmail;
        user.passwordHash = passwordEncoder.encode(password);
        user.role = Enums.UserRole.USER;
        user.active = true;
        user.emailVerified = !requireEmailVerification;
        if (requireEmailVerification) {
            issueEmailOtp(user);
        }
        user = users.save(user);

        Organization organization = organizationService.createOrganization(
                user,
                organizationName == null || organizationName.isBlank() ? name + " Workspace" : organizationName,
                normalizedEmail
        );
        Subscription subscription = billingService.startTrial(user, planCode);
        if (requireEmailVerification) {
            sendEmailOtp(user);
            audit.log(user, "auth.registered.pending_email", "User", user.id, user.email);
            return emailVerificationPayload(user, organization, subscription);
        }
        audit.log(user, "auth.registered", "User", user.id, user.email);
        String token = tokenService.createToken(user, "register");
        Map<String, Object> payload = authPayload(user, organization, token);
        payload.put("subscription", ApiMappers.subscription(subscription));
        return payload;
    }

    @Transactional
    public Map<String, Object> login(String email, String password) {
        UserEntity user = users.findByEmailIgnoreCase(normalizeEmail(email))
                .orElseThrow(() -> ApiException.unauthorized("Identifiants invalides"));
        if (!passwordEncoder.matches(password, user.passwordHash)) {
            throw ApiException.unauthorized("Identifiants invalides");
        }
        if (!user.active) {
            throw ApiException.forbidden("Compte desactive");
        }
        if (requireEmailVerification && !user.emailVerified) {
            issueEmailOtp(user);
            users.save(user);
            sendEmailOtp(user);
            audit.log(user, "auth.email.verification_required", "User", user.id, user.email);
            return emailVerificationPayload(
                    user,
                    organizationService.currentOrganization(user),
                    billingService.currentSubscription(user)
            );
        }
        if (user.twoFactorEnabled) {
            user.twoFactorCode = otp();
            user.twoFactorCodeExpiresAt = Instant.now().plus(twoFactorTtlMinutes, ChronoUnit.MINUTES);
            users.save(user);
            mail.sendHtml(
                    user.email,
                    "Votre code de connexion Nexora",
                    templates.otp(
                            "Validation en deux étapes",
                            "Utilisez ce code pour terminer votre connexion.",
                            user.twoFactorCode,
                            twoFactorTtlMinutes
                    )
            );
            Map<String, Object> body = Responses.map();
            body.put("requiresTwoFactor", true);
            body.put("email", user.email);
            body.put("expiresAt", user.twoFactorCodeExpiresAt);
            return body;
        }
        String token = tokenService.createToken(user, "login");
        audit.log(user, "auth.login", "User", user.id, user.email);
        return authPayload(user, organizationService.currentOrganization(user), token);
    }

    @Transactional
    public Map<String, Object> verifyTwoFactor(String email, String code) {
        UserEntity user = users.findByEmailIgnoreCase(normalizeEmail(email))
                .orElseThrow(() -> ApiException.unauthorized("Code 2FA invalide"));
        if (user.twoFactorCode == null || !user.twoFactorCode.equals(code)
                || user.twoFactorCodeExpiresAt == null || user.twoFactorCodeExpiresAt.isBefore(Instant.now())) {
            throw ApiException.unauthorized("Code 2FA invalide ou expire");
        }
        user.twoFactorCode = null;
        user.twoFactorCodeExpiresAt = null;
        users.save(user);
        String token = tokenService.createToken(user, "2fa");
        audit.log(user, "auth.2fa.verified", "User", user.id, user.email);
        return authPayload(user, organizationService.currentOrganization(user), token);
    }

    @Transactional
    public void resendEmailOtp(String email) {
        UserEntity user = users.findByEmailIgnoreCase(normalizeEmail(email))
                .orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
        issueEmailOtp(user);
        users.save(user);
        sendEmailOtp(user);
    }

    @Transactional
    public Map<String, Object> verifyEmail(String email, String code) {
        UserEntity user = users.findByEmailIgnoreCase(normalizeEmail(email))
                .orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
        if (user.emailOtp == null || !user.emailOtp.equals(code)
                || user.emailOtpExpiresAt == null || user.emailOtpExpiresAt.isBefore(Instant.now())) {
            throw ApiException.validation("Code OTP invalide ou expire");
        }
        user.emailVerified = true;
        user.emailOtp = null;
        user.emailOtpExpiresAt = null;
        users.save(user);
        audit.log(user, "auth.email.verified", "User", user.id, user.email);
        String token = tokenService.createToken(user, "email-verified");
        Map<String, Object> payload = authPayload(user, organizationService.currentOrganization(user), token);
        payload.put("subscription", ApiMappers.subscription(billingService.currentSubscription(user)));
        return payload;
    }

    @Transactional
    public void forgotPassword(String email) {
        UserEntity user = users.findByEmailIgnoreCase(normalizeEmail(email))
                .orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
        user.resetOtp = otp();
        user.resetOtpExpiresAt = Instant.now().plus(emailOtpTtlMinutes, ChronoUnit.MINUTES);
        users.save(user);
        mail.sendHtml(
                user.email,
                "Réinitialisation de votre mot de passe Nexora",
                templates.otp(
                        "Réinitialiser votre mot de passe",
                        "Utilisez ce code pour choisir un nouveau mot de passe.",
                        user.resetOtp,
                        emailOtpTtlMinutes
                )
        );
    }

    @Transactional
    public void resetPassword(String email, String code, String password) {
        UserEntity user = users.findByEmailIgnoreCase(normalizeEmail(email))
                .orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
        if (user.resetOtp == null || !user.resetOtp.equals(code)
                || user.resetOtpExpiresAt == null || user.resetOtpExpiresAt.isBefore(Instant.now())) {
            throw ApiException.validation("Code reset invalide ou expire");
        }
        user.passwordHash = passwordEncoder.encode(password);
        user.resetOtp = null;
        user.resetOtpExpiresAt = null;
        users.save(user);
        audit.log(user, "auth.password.reset", "User", user.id, user.email);
    }

    @Transactional
    public void setTwoFactor(UserEntity user, boolean enabled) {
        UserEntity managed = users.findById(user.id).orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
        managed.twoFactorEnabled = enabled;
        managed.twoFactorCode = null;
        managed.twoFactorCodeExpiresAt = null;
        users.save(managed);
        audit.log(managed, enabled ? "auth.2fa.enabled" : "auth.2fa.disabled", "User", managed.id, managed.email);
    }

    @Transactional
    public Map<String, Object> updateProfile(UserEntity user, String name, String email) {
        UserEntity managed = users.findById(user.id).orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
        if (name != null && !name.isBlank()) {
            managed.name = name.trim();
        }
        if (email != null && !email.isBlank()) {
            String normalizedEmail = normalizeEmail(email);
            if (!managed.email.equalsIgnoreCase(normalizedEmail)) {
                if (users.existsByEmailIgnoreCase(normalizedEmail)) {
                    throw ApiException.validation("Cet email est deja utilise");
                }
                managed.email = normalizedEmail;
                managed.emailVerified = !requireEmailVerification;
                if (requireEmailVerification) {
                    issueEmailOtp(managed);
                    sendEmailOtp(managed);
                }
            }
        }
        users.save(managed);
        audit.log(managed, "auth.profile.updated", "User", managed.id, managed.email);
        return ApiMappers.user(managed);
    }

    @Transactional
    public void changePassword(UserEntity user, String currentPassword, String newPassword) {
        UserEntity managed = users.findById(user.id).orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
        if (!passwordEncoder.matches(currentPassword, managed.passwordHash)) {
            throw ApiException.unauthorized("Mot de passe actuel invalide");
        }
        managed.passwordHash = passwordEncoder.encode(newPassword);
        users.save(managed);
        audit.log(managed, "auth.password.changed", "User", managed.id, managed.email);
    }

    private Map<String, Object> authPayload(UserEntity user, Organization organization, String token) {
        Map<String, Object> payload = Responses.map();
        payload.put("token", token);
        payload.put("tokenType", "Bearer");
        payload.put("user", ApiMappers.user(user));
        payload.put("organization", ApiMappers.organization(organization));
        return payload;
    }

    private Map<String, Object> emailVerificationPayload(UserEntity user, Organization organization, Subscription subscription) {
        Map<String, Object> payload = Responses.map();
        payload.put("requiresEmailVerification", true);
        payload.put("email", user.email);
        payload.put("expiresAt", user.emailOtpExpiresAt);
        payload.put("user", ApiMappers.user(user));
        payload.put("organization", ApiMappers.organization(organization));
        payload.put("subscription", ApiMappers.subscription(subscription));
        payload.put("message", "Code OTP envoye par email");
        return payload;
    }

    private void issueEmailOtp(UserEntity user) {
        user.emailOtp = otp();
        user.emailOtpExpiresAt = Instant.now().plus(emailOtpTtlMinutes, ChronoUnit.MINUTES);
    }

    private void sendEmailOtp(UserEntity user) {
        mail.sendHtml(
                user.email,
                "Vérifiez votre adresse e-mail Nexora",
                templates.otp(
                        "Confirmez votre adresse e-mail",
                        "Ce code permet de sécuriser et d’activer votre compte.",
                        user.emailOtp,
                        emailOtpTtlMinutes
                )
        );
    }

    private String otp() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw ApiException.validation("Email obligatoire");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
