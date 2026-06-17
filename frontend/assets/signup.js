const TOKEN_KEY = "nexora_access_token";
const params = new URLSearchParams(window.location.search);

const state = {
    plans: [],
    selectedPlan: null,
    pendingEmail: null
};

const elements = {
    form: document.querySelector("#signupForm"),
    intro: document.querySelector("#signupIntro"),
    success: document.querySelector("#signupSuccess"),
    formAlert: document.querySelector("#formAlert"),
    otpPanel: document.querySelector("#otpPanel"),
    otpInput: document.querySelector("#otpInput"),
    otpEmail: document.querySelector("#otpEmail"),
    resendOtpButton: document.querySelector("#resendOtpButton"),
    submitButton: document.querySelector("#createAccountButton"),
    submitLabel: document.querySelector("#submitLabel"),
    password: document.querySelector("#passwordInput"),
    passwordConfirmation: document.querySelector("#passwordConfirmation"),
    passwordQuality: document.querySelector(".password-quality"),
    passwordQualityText: document.querySelector("#passwordQuality"),
    termsError: document.querySelector("#termsError"),
    stepPlanName: document.querySelector("#stepPlanName"),
    planName: document.querySelector("#summaryTitle"),
    planMonogram: document.querySelector("#planMonogram"),
    planPrice: document.querySelector("#planPrice"),
    planPeriod: document.querySelector("#planPeriod"),
    trialTitle: document.querySelector("#trialTitle"),
    trialCopy: document.querySelector("#trialCopy"),
    billingNote: document.querySelector("#billingNote"),
    summaryFeatures: document.querySelector("#summaryFeatures"),
    changePlanButton: document.querySelector("#changePlanButton"),
    planDialog: document.querySelector("#planDialog"),
    closePlanDialog: document.querySelector("#closePlanDialog"),
    planPicker: document.querySelector("#planPicker"),
    successName: document.querySelector("#successName"),
    successOrganization: document.querySelector("#successOrganization"),
    successPlan: document.querySelector("#successPlan"),
    successStatus: document.querySelector("#successStatus")
};

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
}

function formatPrice(plan) {
    const price = Number(plan.priceMonthly || 0);
    if (price === 0) return "Gratuit";
    return new Intl.NumberFormat("fr-FR", {
        style: "currency",
        currency: plan.currency || "XOF",
        maximumFractionDigits: 0
    }).format(price);
}

function planFeatures(plan) {
    const features = [
        `${plan.maxUsers} utilisateur${plan.maxUsers > 1 ? "s" : ""}`,
        `${plan.maxIptvAccounts} source${plan.maxIptvAccounts > 1 ? "s" : ""} IPTV`,
        `${plan.maxConcurrentStreams} stream${plan.maxConcurrentStreams > 1 ? "s" : ""} simultané${plan.maxConcurrentStreams > 1 ? "s" : ""}`,
        `${plan.storageGb} Go de stockage de service`
    ];
    if (plan.accessSummary) features.unshift(plan.accessSummary);
    return features;
}

function renderSelectedPlan() {
    const plan = state.selectedPlan;
    if (!plan) return;

    const free = Number(plan.priceMonthly || 0) === 0;
    const trialDays = Number(plan.trialDays || 7);
    elements.stepPlanName.textContent = plan.name;
    elements.planName.textContent = plan.name;
    elements.planMonogram.textContent = plan.name.charAt(0).toUpperCase();
    elements.planPrice.textContent = formatPrice(plan);
    elements.planPeriod.textContent = free ? "pour toujours" : "/ mois";
    elements.summaryFeatures.innerHTML = planFeatures(plan)
        .map((feature) => `<li>${escapeHtml(feature)}</li>`)
        .join("");
    elements.trialTitle.textContent = free ? "Accès gratuit" : `${trialDays} jours d’essai inclus`;
    elements.trialCopy.textContent = free
        ? "Aucune carte bancaire nécessaire."
        : "Aucun paiement demandé aujourd’hui.";
    elements.billingNote.textContent = free
        ? "Vous pourrez changer de formule depuis votre espace."
        : "La facturation sera confirmée avant la fin de votre essai.";
    elements.submitLabel.textContent = free
        ? "Créer mon compte gratuit"
        : `Créer mon compte et essayer ${plan.name}`;

    const url = new URL(window.location.href);
    url.searchParams.set("plan", plan.code);
    window.history.replaceState({}, "", url);
    renderPlanPicker();
}

function renderPlanPicker() {
    elements.planPicker.innerHTML = state.plans.map((plan) => `
        <button class="picker-plan ${plan.code === state.selectedPlan?.code ? "selected" : ""}" type="button" data-plan="${escapeHtml(plan.code)}">
            <small>${plan.code === "pro" ? "RECOMMANDÉ" : "FORMULE"}</small>
            <h3>${escapeHtml(plan.name)}</h3>
            <strong>${escapeHtml(formatPrice(plan))}</strong>
            <span>${Number(plan.priceMonthly || 0) === 0 ? "sans limite de durée" : "par mois"}</span>
            <ul>
                ${planFeatures(plan).slice(0, 3).map((feature) => `<li>✓ ${escapeHtml(feature)}</li>`).join("")}
            </ul>
        </button>
    `).join("");
}

async function loadPlans() {
    const response = await fetch("/api/billing/plans", { headers: { Accept: "application/json" } });
    const body = await response.json().catch(() => ({}));
    if (!response.ok || body.success === false || !Array.isArray(body.data) || !body.data.length) {
        throw new Error("Les formules sont momentanément indisponibles.");
    }

    state.plans = body.data.filter((plan) => plan.active);
    const requestedCode = params.get("plan");
    state.selectedPlan = state.plans.find((plan) => plan.code === requestedCode)
        || state.plans.find((plan) => plan.code === "basic")
        || state.plans[0];
    renderSelectedPlan();
}

function passwordScore(value) {
    if (!value) return 0;
    let score = value.length >= 8 ? 1 : 0;
    if (/[a-z]/.test(value) && /[A-Z]/.test(value)) score += 1;
    if (/\d/.test(value)) score += 1;
    if (/[^A-Za-z0-9]/.test(value) || value.length >= 12) score += 1;
    return Math.min(score, 4);
}

function updatePasswordQuality() {
    const score = passwordScore(elements.password.value);
    elements.passwordQuality.dataset.score = String(score);
    elements.passwordQualityText.textContent = [
        "Utilisez au moins 8 caractères, une majuscule et un chiffre.",
        "Mot de passe encore trop simple.",
        "Sécurité moyenne. Ajoutez un symbole ou davantage de caractères.",
        "Bon mot de passe.",
        "Excellent mot de passe."
    ][score];
}

function clearErrors() {
    document.querySelectorAll(".signup-field.invalid").forEach((field) => field.classList.remove("invalid"));
    document.querySelectorAll(".field-error").forEach((error) => {
        error.textContent = "";
    });
    elements.termsError.textContent = "";
    elements.formAlert.hidden = true;
}

function showFieldError(name, message) {
    const field = document.querySelector(`[data-field="${name}"]`);
    if (!field) return;
    field.classList.add("invalid");
    field.querySelector(".field-error").textContent = message;
}

function validateOtp(formData) {
    const code = String(formData.get("code") || "").trim();
    if (!/^\d{6}$/.test(code)) {
        showFieldError("code", "Saisissez le code OTP a 6 chiffres.");
        return false;
    }
    return true;
}

function validateForm(formData) {
    let valid = true;
    const name = String(formData.get("name") || "").trim();
    const organizationName = String(formData.get("organizationName") || "").trim();
    const email = String(formData.get("email") || "").trim();
    const password = String(formData.get("password") || "");
    const confirmation = String(formData.get("passwordConfirmation") || "");

    if (name.length < 2) {
        showFieldError("name", "Indiquez votre nom complet.");
        valid = false;
    }
    if (organizationName.length < 2) {
        showFieldError("organizationName", "Donnez un nom à votre espace.");
        valid = false;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
        showFieldError("email", "Saisissez une adresse e-mail valide.");
        valid = false;
    }
    if (password.length < 8) {
        showFieldError("password", "Le mot de passe doit contenir au moins 8 caractères.");
        valid = false;
    }
    if (confirmation !== password) {
        showFieldError("passwordConfirmation", "Les deux mots de passe ne correspondent pas.");
        valid = false;
    }
    if (!formData.has("terms")) {
        elements.termsError.textContent = "Votre accord est nécessaire pour créer le compte.";
        valid = false;
    }
    return valid;
}

async function submitSignup(event) {
    event.preventDefault();
    clearErrors();
    const formData = new FormData(elements.form);
    if (state.pendingEmail) {
        if (!validateOtp(formData)) return;
    } else if (!validateForm(formData) || !state.selectedPlan) {
        return;
    }

    elements.submitButton.classList.add("loading");
    elements.submitButton.disabled = true;

    try {
        const response = state.pendingEmail
            ? await fetch("/api/auth/email/verify", {
                method: "POST",
                headers: {
                    Accept: "application/json",
                    "Content-Type": "application/json"
                },
                body: JSON.stringify({
                    email: state.pendingEmail,
                    code: String(formData.get("code")).trim()
                })
            })
            : await fetch("/api/auth/register", {
                method: "POST",
                headers: {
                    Accept: "application/json",
                    "Content-Type": "application/json"
                },
                body: JSON.stringify({
                    name: String(formData.get("name")).trim(),
                    organizationName: String(formData.get("organizationName")).trim(),
                    email: String(formData.get("email")).trim(),
                    password: String(formData.get("password")),
                    planCode: state.selectedPlan.code
                })
            });
        const body = await response.json().catch(() => ({}));
        if (!response.ok || body.success === false) {
            (body.errors || []).forEach((error) => showFieldError(error.field, error.message));
            throw new Error(body.message === "Validation failed"
                ? "Vérifiez les informations indiquées."
                : body.message || "La création du compte a échoué.");
        }

        if (body.data?.requiresEmailVerification) {
            showEmailVerification(body.data);
            return;
        }

        localStorage.setItem(TOKEN_KEY, body.data.token);
        showSuccess(body.data);
    } catch (error) {
        elements.formAlert.textContent = error.message || "Une erreur réseau empêche la création du compte.";
        elements.formAlert.hidden = false;
        elements.formAlert.scrollIntoView({ behavior: "smooth", block: "center" });
    } finally {
        elements.submitButton.classList.remove("loading");
        elements.submitButton.disabled = false;
    }
}

function showEmailVerification(data) {
    state.pendingEmail = data.email;
    elements.otpEmail.textContent = data.email;
    elements.otpPanel.hidden = false;
    elements.otpInput.required = true;
    elements.submitLabel.textContent = "Verifier mon email";
    const steps = document.querySelectorAll(".signup-steps .step");
    steps[1]?.classList.add("complete");
    steps[2]?.classList.add("active");
    elements.formAlert.textContent = data.message || "Code OTP envoye. Verifiez votre boite email.";
    elements.formAlert.hidden = false;
    elements.otpInput.focus();
    elements.otpPanel.scrollIntoView({ behavior: "smooth", block: "center" });
}

async function resendOtp() {
    if (!state.pendingEmail) return;
    elements.resendOtpButton.disabled = true;
    try {
        const response = await fetch("/api/auth/email/resend", {
            method: "POST",
            headers: {
                Accept: "application/json",
                "Content-Type": "application/json"
            },
            body: JSON.stringify({ email: state.pendingEmail })
        });
        const body = await response.json().catch(() => ({}));
        if (!response.ok || body.success === false) {
            throw new Error(body.message || "Impossible de renvoyer le code OTP.");
        }
        elements.formAlert.textContent = body.message || "Code OTP renvoye.";
        elements.formAlert.hidden = false;
    } catch (error) {
        elements.formAlert.textContent = error.message || "Impossible de renvoyer le code OTP.";
        elements.formAlert.hidden = false;
    } finally {
        elements.resendOtpButton.disabled = false;
    }
}

function showSuccess(data) {
    elements.form.hidden = true;
    elements.intro.hidden = true;
    elements.successName.textContent = data.user?.name?.split(" ")[0] || "vous";
    elements.successOrganization.textContent = data.organization?.name || "Nexora";
    elements.successPlan.textContent = data.subscription?.plan?.name || state.selectedPlan.name;
    elements.successStatus.textContent = data.subscription?.status === "TRIALING"
        ? "Essai activé"
        : "Actif";
    elements.success.hidden = false;
    document.querySelectorAll(".signup-steps .step").forEach((step) => step.classList.add("complete"));
    elements.success.scrollIntoView({ behavior: "smooth", block: "center" });
}

elements.form.addEventListener("submit", submitSignup);
elements.resendOtpButton.addEventListener("click", resendOtp);
elements.password.addEventListener("input", updatePasswordQuality);
document.querySelectorAll("[data-password-toggle]").forEach((button) => {
    button.addEventListener("click", () => {
        const input = document.getElementById(button.dataset.passwordToggle);
        const visible = input.type === "text";
        input.type = visible ? "password" : "text";
        button.textContent = visible ? "Voir" : "Masquer";
        button.setAttribute("aria-label", visible ? "Afficher le mot de passe" : "Masquer le mot de passe");
    });
});
elements.changePlanButton.addEventListener("click", () => elements.planDialog.showModal());
elements.closePlanDialog.addEventListener("click", () => elements.planDialog.close());
elements.planDialog.addEventListener("click", (event) => {
    if (event.target === elements.planDialog) elements.planDialog.close();
});
elements.planPicker.addEventListener("click", (event) => {
    const button = event.target.closest("[data-plan]");
    if (!button) return;
    state.selectedPlan = state.plans.find((plan) => plan.code === button.dataset.plan);
    renderSelectedPlan();
    elements.planDialog.close();
});

const email = params.get("email");
if (email) elements.form.elements.email.value = email;
updatePasswordQuality();
loadPlans().catch((error) => {
    elements.formAlert.textContent = error.message;
    elements.formAlert.hidden = false;
    elements.submitButton.disabled = true;
});
