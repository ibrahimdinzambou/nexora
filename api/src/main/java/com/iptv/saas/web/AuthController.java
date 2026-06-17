package com.iptv.saas.web;

import com.iptv.saas.security.SecurityUtils;
import com.iptv.saas.service.AuthService;
import com.iptv.saas.service.BillingService;
import com.iptv.saas.service.OrganizationService;
import com.iptv.saas.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final TokenService tokens;
    private final OrganizationService organizations;
    private final BillingService billing;

    public AuthController(AuthService auth, TokenService tokens, OrganizationService organizations, BillingService billing) {
        this.auth = auth;
        this.tokens = tokens;
        this.organizations = organizations;
        this.billing = billing;
    }

    @PostMapping("/register")
    public Object register(@Valid @RequestBody RegisterRequest request) {
        return Responses.ok(auth.register(
                request.name(),
                request.email(),
                request.password(),
                request.organizationName(),
                request.planCode()
        ));
    }

    @PostMapping("/login")
    public Object login(@Valid @RequestBody LoginRequest request) {
        return Responses.ok(auth.login(request.email(), request.password()));
    }

    @PostMapping("/2fa/verify")
    public Object verifyTwoFactor(@Valid @RequestBody CodeRequest request) {
        return Responses.ok(auth.verifyTwoFactor(request.email(), request.code()));
    }

    @PostMapping("/forgot-password")
    public Object forgotPassword(@Valid @RequestBody EmailRequest request) {
        auth.forgotPassword(request.email());
        return Responses.message("Code de reset envoye");
    }

    @PostMapping("/reset-password")
    public Object resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        auth.resetPassword(request.email(), request.code(), request.password());
        return Responses.message("Mot de passe modifie");
    }

    @PostMapping("/email/verify")
    public Object verifyEmail(@Valid @RequestBody CodeRequest request) {
        return Responses.ok(auth.verifyEmail(request.email(), request.code()));
    }

    @PostMapping("/email/resend")
    public Object resendEmail(@Valid @RequestBody EmailRequest request) {
        auth.resendEmailOtp(request.email());
        return Responses.message("Code OTP renvoye");
    }

    @GetMapping("/me")
    public Object me() {
        var user = SecurityUtils.currentUser();
        var body = Responses.map();
        body.put("user", ApiMappers.user(user));
        body.put("organization", ApiMappers.organization(organizations.currentOrganization(user)));
        body.put("subscription", ApiMappers.subscription(billing.currentSubscription(user)));
        return Responses.ok(body);
    }

    @PostMapping("/logout")
    public Object logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            tokens.revoke(header.substring(7).trim());
        }
        return Responses.message("Deconnecte");
    }

    @PostMapping("/logout-all")
    public Object logoutAll() {
        tokens.revokeAll(SecurityUtils.currentUser());
        return Responses.message("Toutes les sessions ont ete revoquees");
    }

    @PatchMapping("/profile")
    public Object updateProfile(@Valid @RequestBody ProfileRequest request) {
        return Responses.ok(auth.updateProfile(SecurityUtils.currentUser(), request.name(), request.email()));
    }

    @PostMapping("/password")
    public Object changePassword(@Valid @RequestBody PasswordRequest request) {
        auth.changePassword(SecurityUtils.currentUser(), request.currentPassword(), request.newPassword());
        return Responses.message("Mot de passe modifie");
    }

    @PostMapping("/2fa/enable")
    public Object enableTwoFactor() {
        auth.setTwoFactor(SecurityUtils.currentUser(), true);
        return Responses.message("2FA activee");
    }

    @PostMapping("/2fa/disable")
    public Object disableTwoFactor() {
        auth.setTwoFactor(SecurityUtils.currentUser(), false);
        return Responses.message("2FA desactivee");
    }

    public record RegisterRequest(
            @NotBlank String name,
            @Email @NotBlank String email,
            @Size(min = 6) String password,
            String organizationName,
            String planCode
    ) {
    }

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {
    }

    public record EmailRequest(@Email @NotBlank String email) {
    }

    public record CodeRequest(@Email @NotBlank String email, @NotBlank String code) {
    }

    public record ResetPasswordRequest(@Email @NotBlank String email, @NotBlank String code, @Size(min = 6) String password) {
    }

    public record ProfileRequest(@NotBlank String name, @Email @NotBlank String email) {
    }

    public record PasswordRequest(@NotBlank String currentPassword, @Size(min = 6) String newPassword) {
    }
}
