package com.iptv.saas.service;

import com.iptv.saas.domain.AuthToken;
import com.iptv.saas.domain.UserEntity;
import com.iptv.saas.repository.AuthTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class TokenService {
    private final AuthTokenRepository tokens;
    private final long tokenTtlHours;

    public TokenService(AuthTokenRepository tokens, @Value("${app.security.token-ttl-hours:168}") long tokenTtlHours) {
        this.tokens = tokens;
        this.tokenTtlHours = tokenTtlHours;
    }

    @Transactional
    public String createToken(UserEntity user, String name) {
        String rawToken = UUID.randomUUID() + "." + UUID.randomUUID();
        AuthToken token = new AuthToken();
        token.user = user;
        token.name = name == null ? "api" : name;
        token.tokenHash = hash(rawToken);
        token.expiresAt = Instant.now().plus(tokenTtlHours, ChronoUnit.HOURS);
        tokens.save(token);
        return rawToken;
    }

    @Transactional(readOnly = true)
    public Optional<UserEntity> findValidUser(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return tokens.findByTokenHashAndRevokedAtIsNull(hash(rawToken))
                .filter(token -> token.expiresAt == null || token.expiresAt.isAfter(Instant.now()))
                .map(token -> {
                    UserEntity user = token.user;
                    if (user.currentOrganization != null) {
                        user.currentOrganization.id.toString();
                    }
                    return user;
                });
    }

    @Transactional
    public void revoke(String rawToken) {
        tokens.findByTokenHashAndRevokedAtIsNull(hash(rawToken)).ifPresent(token -> {
            token.revokedAt = Instant.now();
            tokens.save(token);
        });
    }

    @Transactional
    public void revokeAll(UserEntity user) {
        tokens.deleteByUser(user);
    }

    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
