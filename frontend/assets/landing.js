const TOKEN_KEY = "nexora_access_token";
const API_ROOT = "/api";
const header = document.querySelector("#landingHeader");
const plansGrid = document.querySelector("#plansGrid");
const loginLaunchButton = document.querySelector("#loginLaunchButton");
const appLaunchButton = document.querySelector("#appLaunchButton");
const authModal = document.querySelector("#landingAuthModal");
const authForm = document.querySelector("#landingAuthForm");
const authTitle = document.querySelector("#landingAuthTitle");
const authSubtitle = document.querySelector("#landingAuthSubtitle");
const authSubmit = document.querySelector("#landingAuthSubmit");
const authError = document.querySelector("#landingAuthError");
const passwordField = document.querySelector("#landingPasswordField");
const codeField = document.querySelector("#landingCodeField");

const landingState = {
    token: localStorage.getItem(TOKEN_KEY),
    user: null,
    twoFactorEmail: null,
    emailVerificationEmail: null
};

function updateHeader() {
    header.classList.toggle("scrolled", window.scrollY > 30);
}

function setupReveal() {
    const observer = new IntersectionObserver((entries) => {
        entries.forEach((entry) => {
            if (entry.isIntersecting) {
                entry.target.classList.add("visible");
                observer.unobserve(entry.target);
            }
        });
    }, { threshold: 0.12 });
    document.querySelectorAll(".reveal").forEach((element, index) => {
        element.style.transitionDelay = `${Math.min(index % 4, 3) * 70}ms`;
        observer.observe(element);
    });
}

function setupFaq() {
    document.querySelectorAll(".faq-item button").forEach((button) => {
        button.addEventListener("click", () => {
            const item = button.closest(".faq-item");
            const opening = !item.classList.contains("open");
            document.querySelectorAll(".faq-item.open").forEach((openItem) => {
                openItem.classList.remove("open");
                openItem.querySelector("button").setAttribute("aria-expanded", "false");
            });
            item.classList.toggle("open", opening);
            button.setAttribute("aria-expanded", String(opening));
        });
    });
}

function signupUrl(email, plan) {
    const params = new URLSearchParams();
    if (email) params.set("email", email);
    if (plan) params.set("plan", plan);
    const query = params.toString();
    return `/signup.html${query ? `?${query}` : ""}`;
}

function setupSignupForms() {
    document.querySelectorAll("[data-signup-form]").forEach((form) => {
        form.addEventListener("submit", (event) => {
            event.preventDefault();
            const email = new FormData(form).get("email");
            window.location.href = signupUrl(String(email || "").trim());
        });
    });
}

async function api(path, options = {}) {
    const headers = new Headers(options.headers || {});
    headers.set("Accept", "application/json");
    if (options.body) headers.set("Content-Type", "application/json");
    if (landingState.token) headers.set("Authorization", `Bearer ${landingState.token}`);
    const response = await fetch(`${API_ROOT}${path}`, { ...options, headers });
    const body = await response.json().catch(() => ({}));
    if (!response.ok || body.success === false) {
        throw new Error(body.message || "Connexion impossible pour le moment.");
    }
    return body.data ?? body;
}

function setAuthStep(step, email = "") {
    const waitingForCode = step === "2fa" || step === "email";
    passwordField.hidden = waitingForCode;
    passwordField.querySelector("input").required = !waitingForCode;
    codeField.hidden = !waitingForCode;
    codeField.querySelector("input").required = waitingForCode;
    authError.hidden = true;
    authTitle.textContent = waitingForCode ? "Code de verification" : "Connexion securisee";
    authSubtitle.textContent = step === "email"
        ? `Saisissez le code envoye a ${email}.`
        : step === "2fa"
            ? `Saisissez le code de connexion envoye a ${email}.`
            : "Connectez-vous avec votre compte Nexora pour ouvrir votre espace personnel.";
    authSubmit.textContent = waitingForCode ? "Verifier" : "Se connecter";
}

function resetAuthForm() {
    landingState.twoFactorEmail = null;
    landingState.emailVerificationEmail = null;
    authForm.reset();
    setAuthStep("login");
}

function openAuthModal() {
    resetAuthForm();
    authModal.hidden = false;
    document.body.classList.add("auth-open");
    window.setTimeout(() => authForm.querySelector("input:not([hidden])")?.focus(), 30);
}

function closeAuthModal() {
    authModal.hidden = true;
    document.body.classList.remove("auth-open");
}

function setConnectedUi(user) {
    landingState.user = user || null;
    if (landingState.token && user) {
        appLaunchButton.textContent = "Acceder a mon espace";
        appLaunchButton.href = "/watch.html";
        loginLaunchButton.textContent = user.name || "Mon espace";
        loginLaunchButton.href = "/watch.html";
        return;
    }
    appLaunchButton.textContent = "Ouvrir Nexora";
    appLaunchButton.href = "/watch.html?mode=login";
    loginLaunchButton.textContent = "Connexion";
    loginLaunchButton.href = "/watch.html?mode=login";
}

async function verifyStoredSession() {
    if (!landingState.token) {
        setConnectedUi(null);
        return;
    }
    try {
        const profile = await api("/auth/me");
        setConnectedUi(profile.user);
    } catch {
        landingState.token = null;
        localStorage.removeItem(TOKEN_KEY);
        setConnectedUi(null);
    }
}

async function submitAuth(event) {
    event.preventDefault();
    const formData = new FormData(authForm);
    authError.hidden = true;
    authSubmit.classList.add("loading");
    try {
        let data;
        if (landingState.twoFactorEmail) {
            data = await api("/auth/2fa/verify", {
                method: "POST",
                body: JSON.stringify({
                    email: landingState.twoFactorEmail,
                    code: formData.get("code")
                })
            });
        } else if (landingState.emailVerificationEmail) {
            data = await api("/auth/email/verify", {
                method: "POST",
                body: JSON.stringify({
                    email: landingState.emailVerificationEmail,
                    code: formData.get("code")
                })
            });
        } else {
            data = await api("/auth/login", {
                method: "POST",
                body: JSON.stringify({
                    email: formData.get("email"),
                    password: formData.get("password")
                })
            });
        }

        if (data.requiresEmailVerification) {
            landingState.emailVerificationEmail = data.email;
            setAuthStep("email", data.email);
            return;
        }
        if (data.requiresTwoFactor) {
            landingState.twoFactorEmail = data.email;
            setAuthStep("2fa", data.email);
            return;
        }
        if (!data.token) throw new Error("Reponse API incomplete.");
        landingState.token = data.token;
        localStorage.setItem(TOKEN_KEY, data.token);
        window.location.href = "/watch.html";
    } catch (error) {
        authError.textContent = error.message;
        authError.hidden = false;
    } finally {
        authSubmit.classList.remove("loading");
    }
}

function setupAuthLinks() {
    [loginLaunchButton, appLaunchButton].forEach((link) => {
        link.addEventListener("click", (event) => {
            if (landingState.token) return;
            event.preventDefault();
            openAuthModal();
        });
    });
    authForm.addEventListener("submit", submitAuth);
    authModal.addEventListener("click", (event) => {
        if (event.target.closest("[data-close-auth]")) closeAuthModal();
    });
    document.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && !authModal.hidden) closeAuthModal();
    });
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

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
}

function renderPlans(plans) {
    const activePlans = plans.filter((plan) => plan.active);
    plansGrid.innerHTML = activePlans.map((plan) => {
        const featured = plan.code === "pro";
        const price = formatPrice(plan);
        const period = Number(plan.priceMonthly || 0) === 0 ? "pour commencer" : "/ mois";
        return `
            <article class="plan-card ${featured ? "featured" : ""}">
                <div class="plan-label">
                    <h3>${escapeHtml(plan.name)}</h3>
                    ${featured ? "<span>LE PLUS COMPLET</span>" : ""}
                </div>
                <div class="plan-price">
                    <strong>${escapeHtml(price)}</strong>
                    <span>${period}</span>
                </div>
                <ul class="plan-features">
                    ${plan.accessSummary ? `<li>${escapeHtml(plan.accessSummary)}</li>` : ""}
                    <li>${plan.maxUsers} utilisateur${plan.maxUsers > 1 ? "s" : ""} inclus</li>
                    <li>${plan.maxIptvAccounts} source${plan.maxIptvAccounts > 1 ? "s" : ""} IPTV</li>
                    <li>${plan.maxConcurrentStreams} stream${plan.maxConcurrentStreams > 1 ? "s" : ""} simultané${plan.maxConcurrentStreams > 1 ? "s" : ""}</li>
                    <li>${plan.storageGb} Go de stockage de service</li>
                    <li>${plan.trialDays} jours d’essai</li>
                </ul>
                <button class="plan-action" type="button" data-plan="${escapeHtml(plan.code)}">
                    ${Number(plan.priceMonthly || 0) === 0 ? "Commencer" : "Choisir cette offre"}
                </button>
            </article>
        `;
    }).join("");
    plansGrid.querySelectorAll("[data-plan]").forEach((button) => {
        button.addEventListener("click", () => {
            window.location.href = signupUrl("", button.dataset.plan);
        });
    });
}

async function loadPlans() {
    try {
        const response = await fetch("/api/billing/plans", { headers: { Accept: "application/json" } });
        const body = await response.json();
        if (!response.ok || body.success === false || !Array.isArray(body.data)) throw new Error();
        renderPlans(body.data);
    } catch {
        renderPlans([
            { code: "free", name: "Free", priceMonthly: 0, currency: "XOF", trialDays: 7, maxUsers: 1, maxIptvAccounts: 1, maxConcurrentStreams: 1, storageGb: 1, active: true },
            { code: "basic", name: "Basic", priceMonthly: 5000, currency: "XOF", trialDays: 7, maxUsers: 2, maxIptvAccounts: 1, maxConcurrentStreams: 1, storageGb: 5, active: true },
            { code: "pro", name: "Pro", priceMonthly: 15000, currency: "XOF", trialDays: 7, maxUsers: 5, maxIptvAccounts: 3, maxConcurrentStreams: 3, storageGb: 20, active: true },
            { code: "enterprise", name: "Enterprise", priceMonthly: 50000, currency: "XOF", trialDays: 7, maxUsers: 25, maxIptvAccounts: 10, maxConcurrentStreams: 10, storageGb: 100, active: true }
        ]);
    }
}

window.addEventListener("scroll", updateHeader, { passive: true });
updateHeader();
setupReveal();
setupFaq();
setupSignupForms();
setupAuthLinks();
verifyStoredSession();
loadPlans();
