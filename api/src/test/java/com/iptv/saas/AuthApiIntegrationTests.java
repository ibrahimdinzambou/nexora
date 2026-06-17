package com.iptv.saas;

import com.iptv.saas.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiIntegrationTests {
    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Test
    void docsArePublic() throws Exception {
        mvc.perform(get("/api/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.title").value("IPTV SaaS API"));
    }

    @Test
    void openApiIsPublic() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("IPTV SaaS API"));

        mvc.perform(get("/api/documentation"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void clientFrontendIsNotBundledWithApiRepository() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));

        mvc.perform(get("/index.html"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void invalidCredentialsReturnUnauthorized() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "test@example.com",
                                  "password": "incorrect"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.message").value("Identifiants invalides"));
    }

    @Test
    void adminCanLoginAndReadProfile() throws Exception {
        String token = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "admin@example.com",
                                  "password": "password"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", not(blankOrNullString())))
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll(".*\\\"token\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.email").value("admin@example.com"));
    }

    @Test
    void visitorCanRegisterWithSelectedPlan() throws Exception {
        String email = "signup-" + UUID.randomUUID() + "@example.com";

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Nouvel Utilisateur",
                                  "organizationName": "Espace Familial",
                                  "email": "%s",
                                  "password": "Nexora2026!",
                                  "planCode": "pro"
                                }
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresEmailVerification").value(true))
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.user.email").value(email))
                .andExpect(jsonPath("$.data.user.emailVerified").value(false))
                .andExpect(jsonPath("$.data.organization.name").value("Espace Familial"))
                .andExpect(jsonPath("$.data.subscription.plan.code").value("pro"))
                .andExpect(jsonPath("$.data.subscription.status").value("TRIALING"))
                .andExpect(jsonPath("$.data.subscription.trialEndsAt", not(blankOrNullString())));

        String code = users.findByEmailIgnoreCase(email).orElseThrow().emailOtp;
        assertNotNull(code);

        mvc.perform(post("/api/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "code": "%s"
                                }
                                """.formatted(email, code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", not(blankOrNullString())))
                .andExpect(jsonPath("$.data.user.emailVerified").value(true))
                .andExpect(jsonPath("$.data.subscription.plan.code").value("pro"));
    }

    @Test
    void userWithTwoFactorMustVerifyCodeBeforeSessionIsIssued() throws Exception {
        var user = users.findByEmailIgnoreCase("test@example.com").orElseThrow();
        user.twoFactorEnabled = true;
        user.twoFactorCode = null;
        user.twoFactorCodeExpiresAt = null;
        users.save(user);

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "test@example.com",
                                  "password": "password"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresTwoFactor").value(true))
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.email").value("test@example.com"));

        String code = users.findByEmailIgnoreCase("test@example.com").orElseThrow().twoFactorCode;
        assertNotNull(code);

        mvc.perform(post("/api/auth/2fa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "test@example.com",
                                  "code": "%s"
                                }
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", not(blankOrNullString())))
                .andExpect(jsonPath("$.data.user.email").value("test@example.com"));
    }
}
