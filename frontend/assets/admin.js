const API_ROOT = "/api";
const TOKEN_KEY = "nexora_access_token";
const NETWORK_RETRY_DELAYS = [300, 900];
const ADMIN_ROLES = ["SUPER_ADMIN", "ADMIN", "BILLING", "SUPPORT", "OPS"];
const ACCOUNT_PAGE_SIZES = [12, 24, 48, 96];
const CONNECTOR_OPTIONS = [
    {
        code: "smtp",
        name: "Serveur SMTP",
        family: "E-mail",
        description: "Envoi des OTP, factures et notifications transactionnelles.",
        statusPath: ["smtp"],
        configuredKey: "configured"
    },
    {
        code: "telegram-alerts",
        name: "Alertes Telegram",
        family: "Alertes",
        description: "Notifications opérationnelles pour paiements, support et supervision.",
        statusPath: ["telegram"],
        configuredKey: "configured"
    },
    {
        code: "telegram-admin",
        name: "Bot admin Telegram",
        family: "Pilotage",
        description: "Commandes administratives et décisions rapides depuis Telegram.",
        statusPath: ["telegramAdmin"],
        configuredKey: "configured"
    },
    {
        code: "torbox",
        name: "TorBox",
        family: "Résolution",
        description: "Résolution serveur des flux torrent communautaires compatibles.",
        statusPath: ["torbox"],
        configuredKey: "configured"
    }
];

const state = {
    token: localStorage.getItem(TOKEN_KEY),
    user: null,
    view: "dashboard",
    customerTab: "organizations",
    billingTab: "payments",
    accountFilter: "all",
    accountPage: 1,
    accountPageSize: 24,
    pendingTwoFactorEmail: null,
    selectedTicketId: null,
    dashboard: {},
    accounts: [],
    sessions: [],
    organizations: [],
    users: [],
    subscriptions: [],
    payments: [],
    plans: [],
    invoices: [],
    methods: [],
    tickets: [],
    uptime: [],
    audits: [],
    opsHealth: {},
    opsMetrics: {},
    integrations: {},
    addons: [],
    legalDocuments: [],
    catalogCategories: []
};

const el = {
    gate: document.querySelector("#adminGate"),
    shell: document.querySelector("#adminShell"),
    loginForm: document.querySelector("#adminLoginForm"),
    loginError: document.querySelector("#loginError"),
    twoFactorField: document.querySelector("#twoFactorField"),
    twoFactorCode: document.querySelector("[name='twoFactorCode']"),
    gateSubmit: document.querySelector(".gate-submit"),
    sidebar: document.querySelector("#adminSidebar"),
    nav: document.querySelector(".admin-nav"),
    viewTitle: document.querySelector("#viewTitle"),
    viewKicker: document.querySelector("#viewKicker"),
    search: document.querySelector("#globalSearch"),
    refresh: document.querySelector("#refreshView"),
    clock: document.querySelector("#adminClock"),
    operatorName: document.querySelector("#operatorName"),
    operatorRole: document.querySelector("#operatorRole"),
    operatorAvatar: document.querySelector("#operatorAvatar"),
    networkStatus: document.querySelector("#networkStatus"),
    networkMeta: document.querySelector("#networkMeta"),
    accountNavCount: document.querySelector("#accountNavCount"),
    paymentNavCount: document.querySelector("#paymentNavCount"),
    ticketNavCount: document.querySelector("#ticketNavCount"),
    dashboardMetrics: document.querySelector("#dashboardMetrics"),
    recentPayments: document.querySelector("#recentPayments"),
    recentTickets: document.querySelector("#recentTickets"),
    capacityRing: document.querySelector("#capacityRing"),
    capacityRate: document.querySelector("#capacityRate"),
    activeStreams: document.querySelector("#activeStreamsValue"),
    capacity: document.querySelector("#capacityValue"),
    healthyAccounts: document.querySelector("#healthyAccountsValue"),
    accountGrid: document.querySelector("#accountGrid"),
    accountResultMeta: document.querySelector("#accountResultMeta"),
    accountPageSize: document.querySelector("#accountPageSize"),
    accountPagination: document.querySelector("#accountPagination"),
    runIptvAuditButton: document.querySelector("#runIptvAuditButton"),
    sessionsTable: document.querySelector("#sessionsTable"),
    sessionCountLabel: document.querySelector("#sessionCountLabel"),
    customersHead: document.querySelector("#customersHead"),
    customersTable: document.querySelector("#customersTable"),
    billingMetrics: document.querySelector("#billingMetrics"),
    billingPlanStudio: document.querySelector("#billingPlanStudio"),
    billingHead: document.querySelector("#billingHead"),
    billingTable: document.querySelector("#billingTable"),
    billingTableKicker: document.querySelector("#billingTableKicker"),
    billingTableTitle: document.querySelector("#billingTableTitle"),
    billingAddButton: document.querySelector("#billingAddButton"),
    ticketList: document.querySelector("#ticketList"),
    ticketStatusFilter: document.querySelector("#ticketStatusFilter"),
    conversation: document.querySelector("#conversationPanel"),
    opsMetrics: document.querySelector("#opsMetrics"),
    uptimeList: document.querySelector("#uptimeList"),
    auditList: document.querySelector("#auditList"),
    smtpStatusBadge: document.querySelector("#smtpStatusBadge"),
    smtpFacts: document.querySelector("#smtpFacts"),
    smtpTestForm: document.querySelector("#smtpTestForm"),
    smtpTestEmail: document.querySelector("#smtpTestEmail"),
    templateTestButton: document.querySelector("#templateTestButton"),
    telegramStatusBadge: document.querySelector("#telegramStatusBadge"),
    telegramFacts: document.querySelector("#telegramFacts"),
    telegramTestButton: document.querySelector("#telegramTestButton"),
    telegramAdminTestButton: document.querySelector("#telegramAdminTestButton"),
    torboxStatusBadge: document.querySelector("#torboxStatusBadge"),
    torboxFacts: document.querySelector("#torboxFacts"),
    addonInstallButton: document.querySelector("#addonInstallButton"),
    addonGrid: document.querySelector("#addonGrid"),
    legalGrid: document.querySelector("#legalGrid"),
    modal: document.querySelector("#adminModal"),
    modalKicker: document.querySelector("#modalKicker"),
    modalTitle: document.querySelector("#modalTitle"),
    dynamicForm: document.querySelector("#dynamicForm"),
    toast: document.querySelector("#adminToast")
};

const viewMeta = {
    dashboard: ["Vue générale", "SITUATION EN TEMPS RÉEL"],
    iptv: ["Infrastructure IPTV", "SOURCES, CAPACITÉ ET SESSIONS"],
    customers: ["Clients & accès", "ORGANISATIONS ET UTILISATEURS"],
    billing: ["Facturation", "REVENUS ET ABONNEMENTS"],
    support: ["Centre de support", "CONVERSATIONS CLIENTS"],
    ops: ["Opérations", "SANTÉ ET TRAÇABILITÉ"],
    integrations: ["Connecteurs", "CANAUX ET ADD-ONS COMMUNAUTAIRES"],
    legal: ["Documents légaux", "PUBLICATION ET CONFORMITÉ"]
};

async function api(path, options = {}) {
    const headers = new Headers(options.headers || {});
    headers.set("Accept", "application/json");
    if (options.body && !(options.body instanceof FormData)) headers.set("Content-Type", "application/json");
    if (state.token) headers.set("Authorization", `Bearer ${state.token}`);
    const response = await fetchWithRetry(`${API_ROOT}${path}`, { ...options, headers });
    const body = await response.json().catch(() => ({}));
    if (!response.ok || body.success === false) {
        const error = new Error(body.message || "L'opération a échoué.");
        error.status = response.status;
        throw error;
    }
    return body.data ?? body;
}

async function fetchWithRetry(url, options) {
    const method = String(options.method || "GET").toUpperCase();
    const canRetry = method === "GET" && !options.body;

    for (let attempt = 0; ; attempt += 1) {
        try {
            const response = await fetch(url, options);
            const transientStatus = [502, 503, 504].includes(response.status);
            if (!canRetry || !transientStatus || attempt >= NETWORK_RETRY_DELAYS.length) {
                return response;
            }
        } catch (error) {
            const aborted = error?.name === "AbortError" || options.signal?.aborted;
            if (!canRetry || aborted || attempt >= NETWORK_RETRY_DELAYS.length) {
                throw error;
            }
        }
        await new Promise((resolve) => window.setTimeout(resolve, NETWORK_RETRY_DELAYS[attempt]));
    }
}

function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, character => ({
        "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;"
    })[character]);
}

function dateLabel(value, withTime = false) {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "—";
    return new Intl.DateTimeFormat("fr-FR", {
        day: "2-digit", month: "short", year: withTime ? undefined : "numeric",
        hour: withTime ? "2-digit" : undefined, minute: withTime ? "2-digit" : undefined
    }).format(date);
}

function money(value, currency = "EUR") {
    return new Intl.NumberFormat("fr-FR", { style: "currency", currency: currency || "EUR" })
        .format(Number(value || 0));
}

function statusClass(status) {
    const value = String(status || "").toUpperCase();
    if (["ACTIVE", "VERIFIED", "OK", "ANSWERED", "SENT", "DOWNLOADED"].includes(value)) return "good";
    if (["PENDING", "TRIALING", "OPEN", "ISSUED", "UNKNOWN", "DEGRADED"].includes(value)) return "warn";
    if (["SUSPENDED", "REJECTED", "EXPIRED", "CANCELLED", "PAST_DUE", "DOWN", "CLOSED"].includes(value)) return "bad";
    return "";
}

function badge(status) {
    return `<span class="badge ${statusClass(status)}">${escapeHtml(String(status || "—").replaceAll("_", " "))}</span>`;
}

function emptyRow(columns, label = "Aucune donnée disponible") {
    return `<tr><td class="empty-row" colspan="${columns}">${escapeHtml(label)}</td></tr>`;
}

function icon(name) {
    const paths = {
        clients: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8"/>',
        revenue: '<path d="M12 2v20M17 5H9.5a3.5 3.5 0 0 0 0 7H14a3.5 3.5 0 0 1 0 7H6"/>',
        stream: '<path d="M4 6h16v12H4zM9 22h6M12 18v4M8 3l4 3 4-3"/>',
        ticket: '<path d="M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4z"/>',
        server: '<rect x="3" y="4" width="18" height="6" rx="1"/><rect x="3" y="14" width="18" height="6" rx="1"/><path d="M7 7h.01M7 17h.01"/>',
        audit: '<path d="M12 8v4l3 2"/><circle cx="12" cy="12" r="9"/>',
        addon: '<path d="M8 3h8v5h5v8h-5v5H8v-5H3V8h5z"/>',
        connector: '<path d="M9 7H7a4 4 0 0 0 0 8h2M15 7h2a4 4 0 0 1 0 8h-2M8 12h8"/>',
        check: '<path d="m5 12 4 4L19 6"/>'
    };
    return `<svg viewBox="0 0 24 24">${paths[name] || paths.server}</svg>`;
}

function metricCard(label, value, note, type = "server") {
    return `<article class="metric-card"><div class="metric-card-head"><span>${escapeHtml(label)}</span><span class="metric-icon">${icon(type)}</span></div><strong class="metric-value">${escapeHtml(value)}</strong><span class="metric-note">${note}</span></article>`;
}

function showToast(message, error = false) {
    el.toast.textContent = message;
    el.toast.classList.toggle("error", error);
    el.toast.hidden = false;
    clearTimeout(showToast.timer);
    showToast.timer = setTimeout(() => { el.toast.hidden = true; }, 3600);
}

function showGate(message = "") {
    el.shell.hidden = true;
    el.gate.hidden = false;
    clearTwoFactorMode();
    el.loginError.hidden = !message;
    el.loginError.textContent = message;
}

function showShell() {
    el.gate.hidden = true;
    el.shell.hidden = false;
    el.operatorName.textContent = state.user.name;
    el.operatorRole.textContent = state.user.role.replaceAll("_", " ");
    el.operatorAvatar.textContent = state.user.name?.charAt(0).toUpperCase() || "A";
}

function setTwoFactorMode(data) {
    state.pendingTwoFactorEmail = data.email;
    if (el.twoFactorField) el.twoFactorField.hidden = false;
    if (el.twoFactorCode) {
        el.twoFactorCode.required = true;
        el.twoFactorCode.focus();
    }
    if (el.gateSubmit) el.gateSubmit.innerHTML = "Valider le code 2FA <span>→</span>";
    el.loginError.textContent = `Code envoyé à ${data.email}.`;
    el.loginError.hidden = false;
}

function clearTwoFactorMode() {
    state.pendingTwoFactorEmail = null;
    if (el.twoFactorField) el.twoFactorField.hidden = true;
    if (el.twoFactorCode) {
        el.twoFactorCode.required = false;
        el.twoFactorCode.value = "";
    }
    if (el.gateSubmit) el.gateSubmit.innerHTML = "Entrer dans la console <span>→</span>";
}

async function completeLogin(data) {
    if (!data.token) throw new Error("Réponse d'authentification incomplète.");
    state.token = data.token;
    localStorage.setItem(TOKEN_KEY, data.token);
    clearTwoFactorMode();
    await authenticate();
}

async function login(event) {
    event.preventDefault();
    const form = new FormData(el.loginForm);
    const email = String(form.get("email") || "").trim().toLowerCase();
    const code = String(form.get("twoFactorCode") || "").trim();
    el.loginError.hidden = true;
    try {
        if (state.pendingTwoFactorEmail && email === state.pendingTwoFactorEmail) {
            if (!/^\d{6}$/.test(code)) {
                throw new Error("Saisissez le code 2FA à 6 chiffres reçu par e-mail.");
            }
            const data = await api("/auth/2fa/verify", {
                method: "POST",
                body: JSON.stringify({ email: state.pendingTwoFactorEmail, code })
            });
            await completeLogin(data);
            return;
        }
        if (state.pendingTwoFactorEmail && email !== state.pendingTwoFactorEmail) {
            clearTwoFactorMode();
        }
        const data = await api("/auth/login", {
            method: "POST",
            body: JSON.stringify({ email, password: form.get("password") })
        });
        if (data.requiresTwoFactor) {
            setTwoFactorMode(data);
            return;
        }
        if (data.requiresEmailVerification) throw new Error("Vérifiez d'abord votre e-mail depuis l'espace client.");
        await completeLogin(data);
    } catch (error) {
        el.loginError.textContent = error.message;
        el.loginError.hidden = false;
    }
}

async function authenticate() {
    if (!state.token) return showGate();
    try {
        const profile = await api("/auth/me");
        if (!ADMIN_ROLES.includes(profile.user?.role)) {
            localStorage.removeItem(TOKEN_KEY);
            state.token = null;
            return showGate("Ce compte ne possède pas d'accès administratif.");
        }
        state.user = profile.user;
        showShell();
        await switchView("dashboard");
    } catch {
        localStorage.removeItem(TOKEN_KEY);
        state.token = null;
        showGate("Votre session a expiré. Reconnectez-vous.");
    }
}

async function switchView(view) {
    state.view = view;
    el.viewTitle.textContent = viewMeta[view][0];
    el.viewKicker.textContent = viewMeta[view][1];
    el.search.value = "";
    document.querySelectorAll("[data-admin-view]").forEach(section => section.classList.toggle("active", section.dataset.adminView === view));
    document.querySelectorAll("[data-view]").forEach(button => button.classList.toggle("active", button.dataset.view === view));
    el.sidebar.classList.remove("open");
    el.refresh.classList.add("loading");
    try {
        await loaders[view]();
    } catch (error) {
        showToast(error.message, true);
        if (error.status === 401 || error.status === 403) showGate(error.message);
    } finally {
        el.refresh.classList.remove("loading");
    }
}

async function loadDashboard() {
    state.dashboard = await api("/admin/saas/dashboard");
    const data = state.dashboard;
    const utilization = data.iptv.capacity ? Math.round(data.iptv.streamsActive * 100 / data.iptv.capacity) : 0;
    el.dashboardMetrics.innerHTML = [
        metricCard("Clients actifs", data.clients.active, `<strong>${data.clients.total}</strong> organisations au total`, "clients"),
        metricCard("Revenu vérifié", money(data.billing.verifiedRevenue), `${data.billing.pendingPayments} paiement(s) en attente`, "revenue"),
        metricCard("Streams actifs", data.iptv.streamsActive, `${utilization}% de la capacité utilisée`, "stream"),
        metricCard("Tickets ouverts", data.support.openTickets, "À traiter par l'équipe support", "ticket")
    ].join("");
    el.capacityRing.style.setProperty("--rate", `${Math.min(utilization, 100)}%`);
    el.capacityRate.textContent = `${utilization}%`;
    el.activeStreams.textContent = data.iptv.streamsActive;
    el.capacity.textContent = data.iptv.capacity;
    el.healthyAccounts.textContent = data.iptv.healthyAccounts;
    el.recentPayments.innerHTML = data.recentPayments.length ? data.recentPayments.slice(0, 5).map(payment => `
        <div class="compact-item">
            <span class="compact-mark">${escapeHtml(payment.paymentMethod?.name?.charAt(0) || "€")}</span>
            <div><strong>${escapeHtml(payment.reference)}</strong><small>Organisation #${escapeHtml(payment.organizationId)} · ${dateLabel(payment.createdAt, true)}</small></div>
            <div class="compact-amount"><strong>${money(payment.amount, payment.currency)}</strong><small>${badge(payment.status)}</small></div>
        </div>`).join("") : '<p class="empty-row">Aucun paiement récent</p>';
    el.recentTickets.innerHTML = data.support.recent.length ? data.support.recent.slice(0, 6).map(ticket => `
        <tr data-ticket-open="${ticket.id}"><td><span class="cell-main">${escapeHtml(ticket.subject)}</span><span class="cell-sub">Organisation #${escapeHtml(ticket.organizationId)}</span></td><td>${badge(ticket.priority)}</td><td>${badge(ticket.status)}</td><td>${dateLabel(ticket.updatedAt, true)}</td></tr>
    `).join("") : emptyRow(4, "Aucun ticket récent");
    updateNavCounters();
}

async function loadIptv() {
    [state.accounts, state.sessions] = await Promise.all([api("/admin/accounts"), api("/admin/sessions")]);
    renderAccounts();
    renderSessions();
    el.accountNavCount.textContent = state.accounts.length;
}

function accountIsHealthy(account) {
    return account.active && !account.disabled && ![
        "expired",
        "disabled",
        "stream-failed",
        "misconfigured",
        "empty-catalog",
        "catalog-unavailable",
        "expires-soon",
        "saturated"
    ].includes(String(account.lastHealthStatus || "").toLowerCase());
}

function renderAccounts() {
    let accounts = state.accounts.slice();
    const totalAccounts = accounts.length;
    if (state.accountFilter === "healthy") accounts = accounts.filter(accountIsHealthy);
    if (state.accountFilter === "warning") accounts = accounts.filter(account => !accountIsHealthy(account));
    const query = state.view === "iptv" ? el.search.value.trim().toLocaleLowerCase("fr") : "";
    if (query) accounts = accounts.filter(account => accountSearchText(account).includes(query));

    const filteredTotal = accounts.length;
    state.accountPageSize = ACCOUNT_PAGE_SIZES.includes(Number(state.accountPageSize)) ? Number(state.accountPageSize) : 24;
    const pageCount = Math.max(1, Math.ceil(filteredTotal / state.accountPageSize));
    state.accountPage = Math.min(Math.max(1, state.accountPage), pageCount);
    const startIndex = (state.accountPage - 1) * state.accountPageSize;
    const visibleAccounts = accounts.slice(startIndex, startIndex + state.accountPageSize);

    updateAccountListMeta(filteredTotal, totalAccounts, startIndex, visibleAccounts.length);
    renderAccountPagination(pageCount);
    if (el.accountPageSize) el.accountPageSize.value = String(state.accountPageSize);
    el.accountGrid.innerHTML = visibleAccounts.length
        ? visibleAccounts.map(accountCard).join("")
        : '<article class="admin-panel empty-row">Aucun compte dans ce filtre.</article>';
}

function accountSearchText(account) {
    return [
        account.id,
        account.name,
        account.type,
        account.baseUrl,
        account.playlistUrl,
        account.lastHealthStatus,
        account.expiresAt
    ].filter(Boolean).join(" ").toLocaleLowerCase("fr");
}

function filteredAccountCount() {
    const query = state.view === "iptv" ? el.search.value.trim().toLocaleLowerCase("fr") : "";
    return state.accounts
        .filter(account => state.accountFilter !== "healthy" || accountIsHealthy(account))
        .filter(account => state.accountFilter !== "warning" || !accountIsHealthy(account))
        .filter(account => !query || accountSearchText(account).includes(query))
        .length;
}

function accountCard(account) {
    const healthy = accountIsHealthy(account);
    const max = Number(account.maxStreams || 0);
    const active = Number(account.activeStreams || 0);
    const load = max > 0 ? Math.min(100, Math.round(active * 100 / max)) : 0;
    return `<article class="account-card ${healthy ? "" : "warning"}" data-searchable="${escapeHtml(accountSearchText(account))}">
        <div class="account-card-head">
            <div class="account-provider"><span class="provider-mark">${account.type === "M3U" ? "M3U" : "XT"}</span><div><strong>${escapeHtml(account.name)}</strong><small>${escapeHtml(account.baseUrl || account.playlistUrl || "Source sans URL")}</small></div></div>
            <span class="status-dot ${healthy ? "" : "warning"}">${healthy ? "En ligne" : "Alerte"}</span>
        </div>
        <div class="account-load"><div class="load-label"><span>Charge actuelle</span><strong>${active} / ${max || "∞"}</strong></div><div class="load-bar"><i style="width:${load}%"></i></div></div>
        <dl class="account-facts"><div><dt>Santé</dt><dd>${escapeHtml(account.lastHealthStatus || "inconnue")}</dd></div><div><dt>Échecs</dt><dd>${escapeHtml(account.failureCount || 0)}</dd></div><div><dt>Expire</dt><dd>${dateLabel(account.expiresAt)}</dd></div></dl>
        <div class="account-actions">
            <button class="card-action" data-account-action="status" data-id="${account.id}">Tester</button>
            <button class="card-action" data-account-action="cache" data-id="${account.id}">Vider le cache</button>
            <button class="card-action" data-account-action="limits" data-id="${account.id}">Synchroniser</button>
            <button class="card-action" data-account-action="edit" data-id="${account.id}">Modifier</button>
            <button class="card-action" data-account-action="delete" data-id="${account.id}">Supprimer</button>
        </div>
    </article>`;
}

function updateAccountListMeta(filteredTotal, totalAccounts, startIndex, visibleCount) {
    if (!el.accountResultMeta) return;
    const start = visibleCount ? startIndex + 1 : 0;
    const end = visibleCount ? startIndex + visibleCount : 0;
    const totalLabel = filteredTotal === totalAccounts
        ? `${filteredTotal} compte(s)`
        : `${filteredTotal} sur ${totalAccounts} compte(s)`;
    el.accountResultMeta.textContent = `${start}-${end} affiches - ${totalLabel}`;
}

function renderAccountPagination(pageCount) {
    if (!el.accountPagination) return;
    if (pageCount <= 1) {
        el.accountPagination.innerHTML = "";
        return;
    }
    const page = state.accountPage;
    const buttons = accountPageWindow(pageCount, page).map(item => item === "gap"
        ? '<span class="pager-gap">...</span>'
        : `<button class="${item === page ? "active" : ""}" type="button" data-account-page="${item}" aria-label="Page ${item}" ${item === page ? 'aria-current="page"' : ""}>${item}</button>`
    ).join("");
    el.accountPagination.innerHTML = `
        <button type="button" data-account-page="prev" aria-label="Page precedente" ${page === 1 ? "disabled" : ""}>&lsaquo;</button>
        ${buttons}
        <button type="button" data-account-page="next" aria-label="Page suivante" ${page === pageCount ? "disabled" : ""}>&rsaquo;</button>
    `;
}

function accountPageWindow(pageCount, currentPage) {
    if (pageCount <= 7) {
        return Array.from({ length: pageCount }, (_, index) => index + 1);
    }
    const pages = [1, currentPage - 1, currentPage, currentPage + 1, pageCount]
        .filter(page => page >= 1 && page <= pageCount)
        .filter((page, index, all) => all.indexOf(page) === index)
        .sort((left, right) => left - right);
    return pages.flatMap((page, index) => {
        if (index === 0 || page === pages[index - 1] + 1) return [page];
        return ["gap", page];
    });
}

function renderSessions() {
    el.sessionCountLabel.textContent = `${state.sessions.length} session(s)`;
    el.sessionsTable.innerHTML = state.sessions.length ? state.sessions.map(session => `
        <tr data-searchable="${escapeHtml(`${session.id} ${session.userId} ${session.itemId} ${session.status}`)}">
            <td>#${session.id}</td><td>Utilisateur #${escapeHtml(session.userId)}</td>
            <td><span class="cell-main">${escapeHtml(session.itemId)}</span><span class="cell-sub">${escapeHtml(session.contentType)}</span></td>
            <td>Compte #${escapeHtml(session.iptvAccountId)}</td><td>${badge(session.status)}</td><td>${dateLabel(session.lastHeartbeatAt, true)}</td>
            <td><div class="row-actions">${session.status === "ACTIVE" ? `<button class="row-action negative" data-close-session="${session.id}">Fermer</button>` : ""}</div></td>
        </tr>`).join("") : emptyRow(7, "Aucune session");
}

async function loadCustomers() {
    [state.organizations, state.users, state.subscriptions] = await Promise.all([
        api("/admin/saas/customers"), api("/admin/users"), api("/admin/saas/subscriptions")
    ]);
    renderCustomers();
}

function renderCustomers() {
    if (state.customerTab === "organizations") {
        el.customersHead.innerHTML = "<tr><th>Organisation</th><th>Facturation</th><th>Création</th><th>Statut</th><th></th></tr>";
        el.customersTable.innerHTML = state.organizations.length ? state.organizations.map(item => `
            <tr data-searchable="${escapeHtml(`${item.name} ${item.slug} ${item.billingEmail}`)}"><td><span class="cell-main">${escapeHtml(item.name)}</span><span class="cell-sub">${escapeHtml(item.slug)}</span></td><td>${escapeHtml(item.billingEmail || "—")}</td><td>${dateLabel(item.createdAt)}</td><td>${badge(item.status)}</td><td><div class="row-actions"><button class="row-action ${item.status === "ACTIVE" ? "negative" : "positive"}" data-org-action="${item.status === "ACTIVE" ? "suspend" : "reactivate"}" data-id="${item.id}">${item.status === "ACTIVE" ? "Suspendre" : "Réactiver"}</button></div></td></tr>
        `).join("") : emptyRow(5);
    } else if (state.customerTab === "users") {
        el.customersHead.innerHTML = "<tr><th>Utilisateur</th><th>Rôle</th><th>Organisation</th><th>Statut</th><th>Création</th><th></th></tr>";
        el.customersTable.innerHTML = state.users.length ? state.users.map(user => `
            <tr data-searchable="${escapeHtml(`${user.name} ${user.email} ${user.role}`)}"><td><span class="cell-main">${escapeHtml(user.name)}</span><span class="cell-sub">${escapeHtml(user.email)}</span></td>
            <td><select class="table-select" data-user-role="${user.id}">${["SUPER_ADMIN","ADMIN","BILLING","SUPPORT","OPS","USER"].map(role => `<option value="${role}" ${role === user.role ? "selected" : ""}>${role.replaceAll("_"," ")}</option>`).join("")}</select></td>
            <td>#${escapeHtml(user.currentOrganizationId)}</td><td><button class="row-action ${user.active ? "positive" : "negative"}" data-user-toggle="${user.id}" data-active="${user.active}">${user.active ? "Actif" : "Inactif"}</button></td><td>${dateLabel(user.createdAt)}</td>
            <td><button class="row-action" data-user-categories="${user.id}">Catégories (${user.allowedCategories?.length || 0})</button></td></tr>
        `).join("") : emptyRow(6);
    } else {
        el.customersHead.innerHTML = "<tr><th>ID</th><th>Organisation</th><th>Formule</th><th>Droits</th><th>Statut</th><th>Fin de période</th></tr>";
        el.customersTable.innerHTML = state.subscriptions.length ? state.subscriptions.map(item => `
            <tr data-searchable="${escapeHtml(`${item.id} ${item.organizationId} ${item.plan?.name} ${item.plan?.accessSummary || ""} ${item.status}`)}"><td>#${item.id}</td><td>Organisation #${escapeHtml(item.organizationId)}</td><td><span class="cell-main">${escapeHtml(item.plan?.name || "—")}</span><span class="cell-sub">${money(item.plan?.priceMonthly, item.plan?.currency)}</span></td><td><span class="cell-main">${escapeHtml(item.plan?.accessSummary || "Catalogue complet")}</span><span class="cell-sub">${(item.plan?.entitlements || []).length || "auto"} règle(s)</span></td><td>${badge(item.status)}</td><td>${dateLabel(item.currentPeriodEnd || item.trialEndsAt)}</td></tr>
        `).join("") : emptyRow(6);
    }
}

async function loadBilling() {
    [state.payments, state.plans, state.invoices, state.methods] = await Promise.all([
        api("/admin/billing/payments"), api("/admin/billing/plans"), api("/admin/saas/invoices"), api("/admin/billing/payment-methods")
    ]);
    const pending = state.payments.filter(payment => payment.status === "PENDING");
    const verified = state.payments.filter(payment => payment.status === "VERIFIED");
    el.billingMetrics.innerHTML = [
        metricCard("À vérifier", pending.length, "Paiements en attente de décision", "revenue"),
        metricCard("Revenu vérifié", money(verified.reduce((sum, payment) => sum + Number(payment.amount || 0), 0), verified[0]?.currency), `${verified.length} transaction(s) approuvée(s)`, "revenue"),
        metricCard("Formules actives", state.plans.filter(plan => plan.active).length, `${state.plans.length} formule(s) configurée(s)`, "clients")
    ].join("");
    renderBilling();
    el.paymentNavCount.textContent = pending.length;
}

function renderPlanStudio() {
    if (!el.billingPlanStudio) return;
    const activePlans = state.plans.filter(plan => plan.active).length;
    const trialPlans = state.plans.filter(plan => Number(plan.trialDays || 0) > 0 && Number(plan.priceMonthly || 0) > 0).length;
    el.billingPlanStudio.innerHTML = `
        <section class="studio-rhythm">
            <div>
                <p class="admin-kicker">ATELIER DES OFFRES</p>
                <h2>Formules, essais et droits catalogue</h2>
            </div>
            <div class="studio-scoreboard">
                <span><strong>${activePlans}</strong> actives</span>
                <span><strong>${trialPlans}</strong> essais</span>
                <span><strong>${state.invoices.length}</strong> factures</span>
            </div>
        </section>
        <div class="plan-playbook-grid">
            ${state.plans.length ? state.plans.map(planCard).join("") : '<article class="plan-empty">Aucune formule configurée.</article>'}
        </div>
    `;
}

function planCard(plan) {
    const entitlements = plan.entitlements || [];
    const paid = Number(plan.priceMonthly || 0) > 0;
    const enabledRules = entitlements.filter(entitlement => entitlement.enabled !== false);
    const categoryCount = enabledRules.filter(entitlement => entitlement.mode === "CATEGORY").length;
    const addonCount = enabledRules.filter(entitlement => entitlement.mode === "ADDON").length;
    const connectorCount = enabledRules.filter(entitlement => entitlement.mode === "CONNECTOR").length;
    const accessLabels = enabledRules.length
        ? enabledRules.slice(0, 4).map(entitlementChip).join("")
        : '<span class="access-chip open">Catalogue complet</span>';
    return `<article class="subscription-card ${plan.active ? "" : "muted"}" data-searchable="${escapeHtml(`${plan.name} ${plan.code} ${plan.accessSummary || ""}`)}">
        <div class="subscription-card-top">
            <span class="plan-token">${escapeHtml(String(plan.name || "N").slice(0, 1).toUpperCase())}</span>
            <div><p class="admin-kicker">${escapeHtml(plan.highlight || plan.code)}</p><h3>${escapeHtml(plan.name)}</h3></div>
            ${badge(plan.active ? "ACTIVE" : "INACTIVE")}
        </div>
        <div class="subscription-price"><strong>${money(plan.priceMonthly, plan.currency)}</strong><span>${paid ? `/${Number(plan.billingPeriodDays || 30)} j` : "gratuit"}</span></div>
        <p class="subscription-copy">${escapeHtml(plan.description || "Offre sans description.")}</p>
        <div class="subscription-steps">
            <span><b>${Number(plan.trialDays || 0)}</b><small>jours essai</small></span>
            <span><b>${plan.maxUsers}</b><small>utilisateurs</small></span>
            <span><b>${plan.maxConcurrentStreams}</b><small>streams</small></span>
            <span><b>${categoryCount || "∞"}</b><small>catégories</small></span>
            <span><b>${addonCount}</b><small>add-ons</small></span>
            <span><b>${connectorCount}</b><small>connecteurs</small></span>
        </div>
        <div class="access-track">${accessLabels}</div>
        <button class="admin-button admin-button-small" type="button" data-plan-edit="${plan.id}">Composer</button>
    </article>`;
}

function entitlementChip(entitlement) {
    const mode = String(entitlement.mode || "ALL");
    const label = entitlement.label || entitlement.keyword || entitlement.categoryId || entitlement.contentType || "Accès";
    const tone = mode === "CATEGORY" ? "manual"
        : mode === "KEYWORD" ? "auto"
        : mode === "TYPE" ? "type"
        : mode === "ADDON" ? "addon"
        : mode === "CONNECTOR" ? "connector"
        : "open";
    return `<span class="access-chip ${tone}">${escapeHtml(label)}</span>`;
}

function renderBilling() {
    el.billingAddButton.hidden = !["plans", "methods"].includes(state.billingTab);
    if (el.billingPlanStudio) {
        el.billingPlanStudio.hidden = state.billingTab !== "plans";
        if (state.billingTab === "plans") renderPlanStudio();
    }
    if (state.billingTab === "payments") {
        el.billingTableKicker.textContent = "TRANSACTIONS";
        el.billingTableTitle.textContent = "Paiements à traiter";
        el.billingHead.innerHTML = "<tr><th>Référence</th><th>Organisation</th><th>Montant</th><th>Méthode</th><th>Statut</th><th>Date</th><th></th></tr>";
        el.billingTable.innerHTML = state.payments.length ? state.payments.map(payment => `
            <tr data-searchable="${escapeHtml(`${payment.reference} ${payment.organizationId} ${payment.status}`)}"><td><span class="cell-main">${escapeHtml(payment.reference)}</span><span class="cell-sub">#${payment.id}</span></td><td>#${escapeHtml(payment.organizationId)}</td><td>${money(payment.amount, payment.currency)}</td><td>${escapeHtml(payment.paymentMethod?.name || "—")}</td><td>${badge(payment.status)}</td><td>${dateLabel(payment.createdAt)}</td><td><div class="row-actions">${payment.status === "PENDING" ? `<button class="row-action positive" data-payment-action="verify" data-id="${payment.id}">Valider</button><button class="row-action negative" data-payment-action="reject" data-id="${payment.id}">Rejeter</button>` : ""}</div></td></tr>
        `).join("") : emptyRow(7);
    } else if (state.billingTab === "plans") {
        el.billingTableKicker.textContent = "CATALOGUE TARIFAIRE"; el.billingTableTitle.textContent = "Formules commerciales";
        el.billingAddButton.hidden = false; el.billingAddButton.textContent = "+ Nouvelle formule"; el.billingAddButton.dataset.modalType = "plan";
        el.billingHead.innerHTML = "<tr><th>Formule</th><th>Prix</th><th>Essai</th><th>Durée</th><th>Accès</th><th>Limites</th><th>Statut</th><th></th></tr>";
        el.billingTable.innerHTML = state.plans.length ? state.plans.map(plan => `<tr data-searchable="${escapeHtml(`${plan.name} ${plan.code} ${plan.accessSummary || ""}`)}"><td><span class="cell-main">${escapeHtml(plan.name)}</span><span class="cell-sub">${escapeHtml(plan.code)}</span></td><td>${money(plan.priceMonthly, plan.currency)}</td><td>${Number(plan.trialDays || 0)} jour(s)</td><td>${Number(plan.billingPeriodDays || 30)} jour(s)</td><td><span class="cell-main">${escapeHtml(plan.accessSummary || "Catalogue complet")}</span><span class="cell-sub">${(plan.entitlements || []).length || "auto"} règle(s)</span></td><td>${plan.maxUsers} user · ${plan.maxConcurrentStreams} stream</td><td>${badge(plan.active ? "ACTIVE" : "INACTIVE")}</td><td><button class="row-action" data-plan-edit="${plan.id}">Modifier</button></td></tr>`).join("") : emptyRow(8);
    } else if (state.billingTab === "invoices") {
        el.billingTableKicker.textContent = "DOCUMENTS"; el.billingTableTitle.textContent = "Factures émises";
        el.billingHead.innerHTML = "<tr><th>Numéro</th><th>Organisation</th><th>Montant</th><th>Statut</th><th>Émission</th><th>Téléchargement</th></tr>";
        el.billingTable.innerHTML = state.invoices.length ? state.invoices.map(invoice => `<tr data-searchable="${escapeHtml(`${invoice.invoiceNumber} ${invoice.organizationId} ${invoice.status}`)}"><td><span class="cell-main">${escapeHtml(invoice.invoiceNumber)}</span><span class="cell-sub">Paiement #${escapeHtml(invoice.paymentId)}</span></td><td>#${escapeHtml(invoice.organizationId)}</td><td>${money(invoice.amount, invoice.currency)}</td><td>${badge(invoice.status)}</td><td>${dateLabel(invoice.issuedAt)}</td><td>${dateLabel(invoice.downloadedAt)}</td></tr>`).join("") : emptyRow(6);
    } else {
        el.billingTableKicker.textContent = "ENCAISSEMENT"; el.billingTableTitle.textContent = "Moyens de paiement";
        el.billingAddButton.hidden = false; el.billingAddButton.textContent = "+ Nouvelle méthode"; el.billingAddButton.dataset.modalType = "method";
        el.billingHead.innerHTML = "<tr><th>Méthode</th><th>Code</th><th>Instructions</th><th>Statut</th><th></th></tr>";
        el.billingTable.innerHTML = state.methods.length ? state.methods.map(method => `<tr data-searchable="${escapeHtml(`${method.name} ${method.code}`)}"><td class="cell-main">${escapeHtml(method.name)}</td><td>${escapeHtml(method.code)}</td><td>${escapeHtml(method.instructions || "—")}</td><td>${badge(method.active ? "ACTIVE" : "INACTIVE")}</td><td><button class="row-action" data-method-edit="${method.id}">Modifier</button></td></tr>`).join("") : emptyRow(5);
    }
}

async function loadSupport() {
    [state.tickets, state.users] = await Promise.all([
        api("/admin/support/tickets"),
        api("/admin/users")
    ]);
    renderTickets();
    el.ticketNavCount.textContent = state.tickets.filter(ticket => ticket.status === "OPEN").length;
    if (state.selectedTicketId && state.tickets.some(ticket => ticket.id === state.selectedTicketId)) await openTicket(state.selectedTicketId);
}

function renderTickets() {
    const status = el.ticketStatusFilter.value;
    const tickets = state.tickets.filter(ticket => status === "all" || ticket.status === status);
    el.ticketList.innerHTML = tickets.length ? tickets.map(ticket => `
        <button class="ticket-item ${ticket.id === state.selectedTicketId ? "active" : ""}" type="button" data-ticket-id="${ticket.id}" data-searchable="${escapeHtml(`${ticket.subject} ${ticket.status} ${ticket.priority}`)}">
            <div class="ticket-item-top"><span class="priority-mark ${ticket.priority === "URGENT" ? "urgent" : ""}"></span>${badge(ticket.status)}</div>
            <strong>${escapeHtml(ticket.subject)}</strong><p>Organisation #${escapeHtml(ticket.organizationId)} · ${dateLabel(ticket.updatedAt, true)}</p>
        </button>`).join("") : '<p class="empty-row">Aucun ticket dans ce filtre.</p>';
}

async function openTicket(id) {
    state.selectedTicketId = Number(id);
    renderTickets();
    const detail = await api(`/admin/support/tickets/${id}`);
    const ticket = detail.ticket;
    const assignees = state.users.filter(user => ["SUPER_ADMIN", "ADMIN", "SUPPORT"].includes(user.role) && user.active);
    const assigned = state.users.find(user => user.id === ticket.assignedToId);
    el.conversation.innerHTML = `
        <div class="conversation-head">
            <div><p class="admin-kicker">TICKET #${ticket.id}</p><h2>${escapeHtml(ticket.subject)}</h2><p>Organisation #${escapeHtml(ticket.organizationId)} · Utilisateur #${escapeHtml(ticket.userId)} · Assigné à ${escapeHtml(assigned?.name || "personne")}</p></div>
            <div class="conversation-tools">
                <select class="assignee-select" data-ticket-assignee="${ticket.id}"><option value="">Non assigné</option>${assignees.map(user => `<option value="${user.id}" ${user.id === ticket.assignedToId ? "selected" : ""}>${escapeHtml(user.name)}</option>`).join("")}</select>
                <select data-ticket-status="${ticket.id}">${["OPEN","PENDING","ANSWERED","CLOSED"].map(status => `<option ${status === ticket.status ? "selected" : ""}>${status}</option>`).join("")}</select>
                ${badge(ticket.priority)}
            </div>
        </div>
        <div class="message-list">${detail.messages.length ? detail.messages.map(message => `<div class="message ${message.author?.role !== "USER" ? "staff" : ""} ${message.internal ? "internal" : ""}"><p>${escapeHtml(message.body)}</p><small>${escapeHtml(message.author?.name || "Système")} · ${dateLabel(message.createdAt, true)}${message.internal ? " · Note interne" : ""}</small></div>`).join("") : '<p class="empty-row">Aucun message</p>'}</div>
        <form class="reply-form" data-ticket-reply="${ticket.id}"><textarea name="body" placeholder="Écrire une réponse..." required></textarea><div class="reply-actions"><label class="checkbox-field"><input name="internal" type="checkbox"> Note interne</label><button class="admin-button admin-button-primary" type="submit">Envoyer la réponse</button></div></form>`;
}

async function loadOps() {
    [state.opsHealth, state.opsMetrics, state.uptime, state.audits] = await Promise.all([
        api("/admin/ops/health"), api("/admin/ops/metrics"), api("/admin/ops/uptime-checks"), api("/admin/ops/audit-logs")
    ]);
    el.networkStatus.textContent = state.opsHealth.status === "ok" ? "Réseau actif" : "Réseau dégradé";
    el.networkMeta.textContent = `Vérifié ${dateLabel(state.opsHealth.checkedAt, true)}`;
    el.opsMetrics.innerHTML = [
        metricCard("État plateforme", state.opsHealth.status.toUpperCase(), state.opsHealth.database === "ok" ? "Base de données opérationnelle" : "Base de données à vérifier", "server"),
        metricCard("Capacité flux", `${state.opsMetrics.activeStreams} / ${state.opsMetrics.streamCapacity}`, `${state.opsMetrics.healthyAccounts} compte(s) sain(s)`, "stream"),
        metricCard("Paiements en attente", state.opsMetrics.pendingPayments, "Décisions administratives requises", "revenue"),
        metricCard("Événements audités", state.opsMetrics.auditLogs, `${state.opsMetrics.openTickets} ticket(s) ouvert(s)`, "audit")
    ].join("");
    el.uptimeList.innerHTML = state.uptime.length ? state.uptime.map(check => `<div class="uptime-item" data-searchable="${escapeHtml(`${check.name} ${check.url} ${check.lastStatus}`)}"><span class="uptime-state ${check.lastStatus}"></span><div><strong>${escapeHtml(check.name)}</strong><small>${escapeHtml(check.url)} · ${dateLabel(check.lastCheckedAt, true)}</small></div><span class="latency">${check.lastLatencyMs == null ? "—" : `${check.lastLatencyMs} ms`}</span><button class="row-action" data-run-check="${check.id}">Tester</button></div>`).join("") : '<p class="empty-row">Aucun contrôle configuré</p>';
    el.auditList.innerHTML = state.audits.length ? state.audits.slice(0, 30).map(log => `<div class="audit-item" data-searchable="${escapeHtml(`${log.action} ${log.subjectType} ${log.metadata}`)}"><span class="audit-line"></span><div><strong>${escapeHtml(log.action)}</strong><p>${escapeHtml(log.subjectType || "Système")} #${escapeHtml(log.subjectId || "—")} · ${escapeHtml(log.metadata || "")}</p></div><time>${dateLabel(log.createdAt, true)}</time></div>`).join("") : '<p class="empty-row">Aucune trace d’audit</p>';
}

async function loadIntegrations() {
    const canManageAddons = ["SUPER_ADMIN", "ADMIN"].includes(state.user?.role);
    const canSharePrivateAddons = state.user?.role === "SUPER_ADMIN";
    [state.integrations, state.addons, state.users] = await Promise.all([
        api("/admin/integrations/status"),
        canManageAddons ? api("/admin/addons") : Promise.resolve([]),
        canSharePrivateAddons ? api("/admin/users") : Promise.resolve(state.users || [])
    ]);
    const smtp = state.integrations.smtp;
    const telegram = state.integrations.telegram;
    const telegramAdmin = state.integrations.telegramAdmin || {};
    const torbox = state.integrations.torbox || {};
    el.smtpStatusBadge.textContent = smtp.configured ? "Paramétré" : "Incomplet";
    el.smtpStatusBadge.className = `badge ${smtp.configured ? "good" : "bad"}`;
    el.smtpFacts.innerHTML = connectorFacts([
        ["Serveur", `${smtp.host || "—"}:${smtp.port || "—"}`],
        ["Utilisateur", smtp.username || "Non défini"],
        ["Expéditeur", smtp.fromAddress || "Non défini"],
        ["Sécurité", smtp.ssl ? "SSL" : smtp.startTls ? "STARTTLS" : "Aucune"],
        ["Authentification", smtp.authentication ? "Activée" : "Désactivée"],
        ["Nom expéditeur", smtp.fromName || "Nexora"]
    ]);
    el.smtpTestEmail.value = state.user?.email || "";
    el.telegramStatusBadge.textContent = telegram.configured ? "Connecté" : telegram.enabled ? "Incomplet" : "Désactivé";
    el.telegramStatusBadge.className = `badge ${telegram.configured ? "good" : "bad"}`;
    el.telegramFacts.innerHTML = connectorFacts([
        ["Activation", telegram.enabled ? "Activée" : "Désactivée"],
        ["Token bot", telegram.botToken || "Non défini"],
        ["Chat cible", telegram.chatId || "Non défini"],
        ["API", telegram.endpoint || "api.telegram.org"]
    ]);
    el.telegramTestButton.disabled = !telegram.configured;
    el.telegramFacts.innerHTML = connectorFacts([
        ["Alertes", telegram.enabled ? "Activees" : "Desactivees"],
        ["Token alertes", telegram.botToken || "Non defini"],
        ["Chat alertes", telegram.chatId || "Non defini"],
        ["Admin", telegramAdmin.enabled ? "Active" : "Desactive"],
        ["Token admin", telegramAdmin.botToken || "Non defini"],
        ["Chats admin", (telegramAdmin.allowedChatIds || []).join(", ") || "Aucun"],
        ["Lecture seule", (telegramAdmin.readOnlyChatIds || []).join(", ") || "Aucun"],
        ["Polling admin", telegramAdmin.polling ? `${telegramAdmin.pollIntervalMs || "?"} ms` : "Inactif"],
        ["API", telegram.endpoint || telegramAdmin.endpoint || "api.telegram.org"]
    ]);
    if (el.telegramAdminTestButton) el.telegramAdminTestButton.disabled = !telegramAdmin.configured;
    el.torboxStatusBadge.textContent = torbox.configured ? "Configuré" : "Clé manquante";
    el.torboxStatusBadge.className = `badge ${torbox.configured ? "good" : "bad"}`;
    el.torboxFacts.innerHTML = connectorFacts([
        ["Fournisseur", torbox.provider || "TorBox"],
        ["API", torbox.endpoint || "https://api.torbox.app/"],
        ["Jeton", torbox.configured ? "Chargé et masqué" : "Non défini"],
        ["Domaines permis", (torbox.allowedDownloadHosts || []).join(", ") || ".torbox.app"]
    ]);
    el.addonInstallButton.hidden = !canManageAddons;
    renderAddons(canManageAddons);
}

function renderAddons(canManage) {
    if (!canManage) {
        el.addonGrid.innerHTML = '<article class="addon-empty"><strong>Accès restreint</strong><p>La gestion des add-ons communautaires est réservée aux administrateurs.</p></article>';
        return;
    }
    el.addonGrid.innerHTML = state.addons.length ? state.addons.map(addon => {
        const status = String(addon.status || "PENDING");
        const baseStatus = { APPROVED: "Publié", PENDING: "À examiner", DISABLED: "Désactivé" }[status] || status;
        const statusLabel = addon.privateUse ? `Privé · ${baseStatus}` : baseStatus;
        const catalogs = (addon.catalogs || []).map(catalog => (
            `<span>${escapeHtml(catalog.type.toUpperCase())} · ${escapeHtml(catalog.name)}</span>`
        )).join("");
        const primaryAction = status === "APPROVED"
            ? `<button class="row-action danger" data-addon-action="disable" data-id="${addon.id}">Désactiver</button>`
            : `<button class="row-action approve" data-addon-action="approve" data-id="${addon.id}">Approuver</button>`;
        const accessAction = addon.privateUse && state.user?.role === "SUPER_ADMIN"
            ? `<button class="row-action" data-addon-action="access" data-id="${addon.id}">Accès restreint (${addon.allowedUserIds?.length || 0})</button>`
            : "";
        return `
            <article class="addon-card status-${status.toLowerCase()}" data-searchable="${escapeHtml(`${addon.name} ${addon.addonKey} ${addon.manifestUrl} ${addon.licenseName || ""}`)}">
                <div class="addon-card-top">
                    <span class="addon-mark">${escapeHtml(String(addon.name || "A").slice(0, 2).toUpperCase())}</span>
                    <div><p class="admin-kicker">${escapeHtml(addon.addonKey)}</p><h3>${escapeHtml(addon.name)}</h3><small>v${escapeHtml(addon.version || "—")}</small></div>
                    <span class="addon-status">${statusLabel}</span>
                </div>
                <p class="addon-description">${escapeHtml(addon.description || "Aucune description fournie par la communauté.")}</p>
                <div class="addon-catalogs">${catalogs || "<span>Aucun catalogue</span>"}</div>
                <dl class="addon-facts">
                    <div><dt>Licence</dt><dd>${escapeHtml(addon.licenseName || "Non déclarée")}</dd></div>
                    <div><dt>Flux autorisés</dt><dd>${escapeHtml(addon.allowedStreamHosts || "Aucun")}</dd></div>
                    <div><dt>Classification</dt><dd>${addon.adultContent ? "18+ déclaré" : "Tout public"}</dd></div>
                    <div><dt>Visibilité</dt><dd>${addon.privateUse ? `Privé · ${escapeHtml(addon.ownerEmail || "propriétaire")}` : "Communautaire"}</dd></div>
                    <div><dt>Dernier contrôle</dt><dd>${dateLabel(addon.lastCheckedAt, true)}</dd></div>
                </dl>
                ${addon.lastError ? `<p class="addon-error">${escapeHtml(addon.lastError)}</p>` : ""}
                <div class="addon-actions">
                    ${primaryAction}
                    ${accessAction}
                    <button class="row-action" data-addon-action="refresh" data-id="${addon.id}">Actualiser</button>
                    <button class="row-action" data-addon-action="edit" data-id="${addon.id}">Règles</button>
                    <button class="row-action danger" data-addon-action="delete" data-id="${addon.id}">Supprimer</button>
                </div>
            </article>
        `;
    }).join("") : '<article class="addon-empty"><strong>Aucun add-on installé</strong><p>Ajoutez un manifeste HTTPS communautaire pour commencer son examen.</p></article>';
}

async function loadLegal() {
    state.legalDocuments = await api("/admin/legal");
    el.legalGrid.innerHTML = state.legalDocuments.length ? state.legalDocuments.map(document => `
        <article class="legal-card" data-searchable="${escapeHtml(`${document.documentType} ${document.title} ${document.content}`)}">
            <div class="legal-card-head"><div><span class="legal-type">${escapeHtml(document.documentType)}</span><h3>${escapeHtml(document.title)}</h3></div>${badge(document.published ? "ACTIVE" : "DRAFT")}</div>
            <p class="legal-excerpt">${escapeHtml(document.content)}</p>
            <div class="legal-card-foot"><small>Mis à jour ${dateLabel(document.updatedAt, true)}</small><button class="row-action" data-legal-edit="${document.id}">Modifier</button></div>
        </article>
    `).join("") : '<article class="admin-panel empty-row">Aucun document légal.</article>';
}

function connectorFacts(items) {
    return items.map(([label, value]) => `<div><dt>${escapeHtml(label)}</dt><dd>${escapeHtml(value)}</dd></div>`).join("");
}

function updateNavCounters() {
    const data = state.dashboard;
    if (!data.iptv) return;
    el.accountNavCount.textContent = data.iptv.accountsTotal;
    el.paymentNavCount.textContent = data.billing.pendingPayments;
    el.ticketNavCount.textContent = data.support.openTickets;
}

const loaders = { dashboard: loadDashboard, iptv: loadIptv, customers: loadCustomers, billing: loadBilling, support: loadSupport, ops: loadOps, integrations: loadIntegrations, legal: loadLegal };

function openModal(type, item = null) {
    const configs = {
        account: {
            kicker: "INFRASTRUCTURE IPTV", title: item ? "Modifier la source" : "Ajouter une source",
            html: `<div class="admin-field-grid">
                ${field("name", "Nom du compte", item?.name, true)}
                ${selectField("type", "Type", ["M3U","XTREAM"], item?.type || "M3U")}
                ${field("baseUrl", "URL de base", item?.baseUrl, false, "url")}
                ${field("playlistUrl", "URL de playlist M3U", item?.playlistUrl, false, "url")}
                ${field("username", "Nom d'utilisateur", "")}${field("password", "Mot de passe", "", false, "password")}
                ${field("maxStreams", "Streams maximum", item?.maxStreams ?? 1, false, "number")}
                ${field("expiresAt", "Date d'expiration", toLocalDateTime(item?.expiresAt), false, "datetime-local")}
                </div>${checkField("active", "Compte actif", item?.active ?? true)}`,
            submit: form => saveAccount(form, item?.id)
        },
        plan: {
            kicker: "OFFRE COMMERCIALE", title: item ? "Modifier la formule" : "Nouvelle formule",
            html: planForm(item),
            submit: form => savePlan(form, item?.id)
        },
        method: {
            kicker: "ENCAISSEMENT", title: item ? "Modifier la méthode" : "Nouvelle méthode",
            html: `${field("code","Code",item?.code,true)}${field("name","Nom",item?.name,true)}${textareaField("instructions","Instructions",item?.instructions)}${checkField("active","Méthode active",item?.active ?? true)}`,
            submit: form => saveMethod(form, item?.id)
        },
        uptime: {
            kicker: "SUPERVISION", title: "Nouveau point de contrôle",
            html: `${field("name","Nom du contrôle","",true)}${field("url","URL à vérifier","https://",true,"url")}${selectField("method","Méthode",["GET","HEAD"],"GET")}${checkField("enabled","Contrôle actif",true)}`,
            submit: saveUptime
        },
        legal: {
            kicker: "GOUVERNANCE", title: item ? "Modifier le document" : "Nouveau document",
            html: `${field("type","Type",item?.documentType || "",true)}${field("title","Titre",item?.title || "",true)}${textareaField("content","Contenu",item?.content || "")}${checkField("published","Document publié",item?.published ?? true)}`,
            submit: form => saveLegal(form, item?.id)
        },
        categories: {
            kicker: "CONTRÔLE D'ACCÈS", title: `Catégories de ${item?.name || "l'utilisateur"}`,
            html: categorySelectField(state.catalogCategories, item?.allowedCategories || []),
            submit: form => saveUserCategories(form, item?.id)
        },
        addon: {
            kicker: "REGISTRE COMMUNAUTAIRE", title: item ? "Règles de confiance" : "Installer un add-on",
            html: `<div class="admin-field-grid">
                ${field("manifestUrl", "URL HTTPS du manifeste", item?.manifestUrl || "https://", true, "url")}
                ${field("licenseName", "Nom de la licence", item?.licenseName || "")}
                ${field("licenseUrl", "Preuve / URL de licence", item?.licenseUrl || "", false, "url")}
                ${textareaField("allowedStreamHosts", "Domaines de flux autorisés", item?.allowedStreamHosts || "")}
                </div>
                <p class="field-hint addon-form-hint">La licence reste obligatoire pour une publication communautaire. En usage privé, l’accès est limité au propriétaire et aux comptes choisis par le super-admin. Séparez les domaines par une virgule.</p>
                ${checkField("privateUse", "Usage privé · accès restreint", item?.privateUse ?? false)}
                ${checkField("adultContent", "Contenu 18+ déclaré", item?.adultContent ?? false)}`,
            submit: form => saveAddon(form, item?.id)
        },
        addonAccess: {
            kicker: "PARTAGE PRIVÉ", title: `Accès à ${item?.name || "l'add-on"}`,
            html: `${addonUserSelectField(item)}
                <p class="field-hint">Le propriétaire ${escapeHtml(item?.ownerEmail || "")} conserve toujours l'accès. Seul le super-admin peut modifier cette liste.</p>`,
            submit: form => saveAddonAccess(form, item?.id)
        }
    };
    const config = configs[type];
    el.modal.dataset.modalType = type;
    el.modalKicker.textContent = config.kicker;
    el.modalTitle.textContent = config.title;
    el.dynamicForm.innerHTML = `${config.html}<div class="modal-actions"><button class="admin-button" type="button" data-close-admin-modal>Annuler</button><button class="admin-button admin-button-primary" type="submit">Enregistrer</button></div>`;
    el.dynamicForm.onsubmit = async event => {
        event.preventDefault();
        try { await config.submit(new FormData(el.dynamicForm)); closeModal(); showToast("Modification enregistrée."); await loaders[state.view](); }
        catch (error) { showToast(error.message, true); }
    };
    el.modal.hidden = false;
}

function planForm(item) {
    return `<div class="plan-modal-shell">
        <div class="admin-field-grid">
            ${field("code","Code",item?.code,true)}
            ${field("name","Nom",item?.name,true)}
            ${field("priceMonthly","Prix",item?.priceMonthly ?? 0,false,"number","0.01")}
            ${field("currency","Devise",item?.currency || "XOF")}
            ${field("trialDays","Essai gratuit (jours)",item?.trialDays ?? 7,false,"number")}
            ${field("billingPeriodDays","Période payée (jours)",item?.billingPeriodDays ?? 30,false,"number")}
            ${field("highlight","Badge commercial",item?.highlight || "")}
            ${field("maxUsers","Utilisateurs max",item?.maxUsers ?? 1,false,"number")}
            ${field("maxIptvAccounts","Comptes IPTV max",item?.maxIptvAccounts ?? 1,false,"number")}
            ${field("maxConcurrentStreams","Streams max / utilisateur",item?.maxConcurrentStreams ?? 1,false,"number")}
            ${field("storageGb","Stockage (Go)",item?.storageGb ?? 1,false,"number")}
            <label class="admin-field full"><span>Description</span><textarea name="description">${escapeHtml(item?.description || "")}</textarea></label>
        </div>
        ${planEntitlementFields(item)}
        ${checkField("active","Formule active",item?.active ?? true)}
    </div>`;
}

function planEntitlementFields(item) {
    const entitlements = item?.entitlements || [];
    const hasExplicitRules = entitlements.length > 0;
    const allAccess = !hasExplicitRules || entitlements.some(entitlement => entitlement.mode === "ALL" && entitlement.enabled !== false);
    const selectedTypes = new Set(entitlements.filter(entitlement => entitlement.mode === "TYPE").map(entitlement => entitlement.contentType));
    const keywords = entitlements.filter(entitlement => entitlement.mode === "KEYWORD").map(entitlement => entitlement.keyword || entitlement.label).filter(Boolean).join(", ");
    const selectedCategories = new Set(entitlements.filter(entitlement => entitlement.mode === "CATEGORY").map(entitlement => entitlement.categoryId));
    const selectedAddons = new Set(entitlements
        .filter(entitlement => entitlement.mode === "ADDON")
        .map(entitlement => normalizeAddonRef(entitlement.categoryId || entitlement.keyword)));
    const selectedConnectors = new Set(entitlements
        .filter(entitlement => entitlement.mode === "CONNECTOR")
        .map(entitlement => entitlement.keyword || entitlement.categoryId)
        .filter(Boolean));
    return `<section class="entitlement-builder">
        <div class="entitlement-builder-head">
            <div><p class="admin-kicker">RECETTE D'ACCÈS</p><h3>Ce que la formule ouvre</h3></div>
            <span>${hasExplicitRules ? `${entitlements.length} règle(s)` : "Catalogue complet"}</span>
        </div>
        <div class="entitlement-lanes">
            <label class="access-toggle open"><input name="entitlementAll" type="checkbox" ${allAccess ? "checked" : ""}><span>${icon("server")}</span><strong>Tout</strong><small>Catalogue complet</small></label>
            <div class="access-toggle-group">
                ${["live","movie","series"].map(type => `<label class="access-toggle type"><input name="entitlementTypes" type="checkbox" value="${type}" ${selectedTypes.has(type) ? "checked" : ""}><span>${icon(type === "live" ? "stream" : "ticket")}</span><strong>${typeLabel(type)}</strong><small>Type de contenu</small></label>`).join("")}
            </div>
        </div>
        <div class="entitlement-grid">
            <label class="admin-field"><span>Automatique par mots-clés</span><input name="entitlementKeywords" value="${escapeHtml(keywords)}" placeholder="sport, kids, cinema"></label>
            <div class="keyword-note"><strong>Règles automatiques</strong><span>Chaque mot-clé ouvre les catégories dont le nom, le type ou l'identifiant correspond.</span></div>
        </div>
        ${categoryCardsField(state.catalogCategories, selectedCategories, {
            name: "entitlementCategories",
            title: "Catégories précises",
            note: "Sélectionnez les rayons inclus dans cette formule. Les cartes 18+ doivent être cochées explicitement."
        })}
        ${addonCardsField(selectedAddons)}
        ${connectorCardsField(selectedConnectors)}
    </section>`;
}

function categoryCardsField(categories, selected, options = {}) {
    const name = options.name || "categories";
    const selectedIds = new Set(selected || []);
    const values = (categories || []).filter(category => category?.id);
    const selectedCount = values.filter(category => selectedIds.has(category.id)).length;
    const groups = ["live", "movie", "series"].map(type => ({
        type,
        categories: values.filter(category => String(category.type || "").toLowerCase() === type)
    })).filter(group => group.categories.length);
    const body = groups.length ? groups.map(group => categoryLane(group.type, group.categories, selectedIds, name)).join("")
        : '<div class="choice-empty">Aucune catégorie disponible pour le moment.</div>';
    return `<section class="choice-section">
        <div class="choice-section-head">
            <div><p class="admin-kicker">CATÉGORIES</p><h4>${escapeHtml(options.title || "Catégories autorisées")}</h4><p>${escapeHtml(options.note || "Une liste vide garde l'accès ouvert à tout le catalogue autorisé par la formule.")}</p></div>
            <span>${selectedCount}/${values.length}</span>
        </div>
        <div class="choice-lanes">${body}</div>
    </section>`;
}

function categoryLane(type, categories, selectedIds, name) {
    const meta = categoryTypeMeta(type);
    return `<div class="choice-lane ${meta.tone}">
        <div class="choice-lane-head"><span>${meta.icon}</span><strong>${meta.label}</strong><small>${categories.length} catégorie(s)</small></div>
        <div class="choice-card-grid">
            ${categories.map(category => categoryCard(category, selectedIds.has(category.id), name)).join("")}
        </div>
    </div>`;
}

function categoryCard(category, checked, name) {
    const meta = categoryTypeMeta(category.type);
    const details = [
        category.source || "Catalogue IPTV",
        category.addonKey ? `Add-on ${category.addonKey}` : null,
        category.filterRequired ? "Filtre requis" : null,
        category.adult ? "18+" : null,
        category.privateUse ? "Privé" : null
    ].filter(Boolean);
    return checkboxCard({
        name,
        value: category.id,
        checked,
        className: "category-choice-card",
        tone: meta.tone,
        iconName: meta.iconName,
        eyebrow: meta.label,
        title: category.name,
        description: category.searchOnly ? "Catalogue disponible via recherche." : "Disponible dans la navigation catalogue.",
        meta: details
    });
}

function addonCardsField(selectedAddons) {
    const addons = state.addons || [];
    const selected = new Set(Array.from(selectedAddons || []).map(normalizeAddonRef));
    const cards = addons.length ? addons.map(addon => {
        const value = addonEntitlementId(addon);
        const catalogs = addon.catalogs || [];
        const status = String(addon.status || "PENDING");
        return checkboxCard({
            name: "entitlementAddons",
            value,
            checked: selected.has(value),
            className: "addon-choice-card",
            tone: status.toLowerCase(),
            iconName: "addon",
            eyebrow: addon.privateUse ? "Add-on privé" : "Add-on communautaire",
            title: addon.name,
            description: addon.description || "Manifeste communautaire contrôlé côté serveur.",
            meta: [
                { APPROVED: "Publié", PENDING: "À examiner", DISABLED: "Désactivé" }[status] || status,
                `${catalogs.length} catalogue(s)`,
                addon.adultContent ? "18+" : "Tout public",
                addon.licenseName || "Licence à vérifier"
            ]
        });
    }).join("") : '<div class="choice-empty">Aucun add-on installable dans les abonnements pour ce rôle.</div>';
    return `<section class="choice-section">
        <div class="choice-section-head">
            <div><p class="admin-kicker">ADD-ONS</p><h4>Add-ons inclus dans l'abonnement</h4><p>Un add-on coché ouvre toutes ses catégories publiées pour cette formule.</p></div>
            <span>${selected.size}/${addons.length}</span>
        </div>
        <div class="choice-card-grid addon-choice-grid">${cards}</div>
    </section>`;
}

function connectorCardsField(selectedConnectors) {
    const selected = new Set(selectedConnectors || []);
    return `<section class="choice-section">
        <div class="choice-section-head">
            <div><p class="admin-kicker">CONNECTEURS</p><h4>Services inclus dans l'offre</h4><p>Ces cartes documentent les connecteurs livrés avec la formule sans donner d'accès catalogue.</p></div>
            <span>${selected.size}/${CONNECTOR_OPTIONS.length}</span>
        </div>
        <div class="choice-card-grid connector-choice-grid">
            ${CONNECTOR_OPTIONS.map(connector => connectorCard(connector, selected.has(connector.code))).join("")}
        </div>
    </section>`;
}

function connectorCard(connector, checked) {
    const status = connectorStatus(connector);
    return checkboxCard({
        name: "entitlementConnectors",
        value: connector.code,
        checked,
        className: "connector-choice-card",
        tone: status.configured ? "configured" : "incomplete",
        iconName: "connector",
        eyebrow: connector.family,
        title: connector.name,
        description: connector.description,
        meta: [status.label, "Connecteur"]
    });
}

function checkboxCard({ name, value, checked, className = "", tone = "", iconName = "server", eyebrow = "", title = "", description = "", meta = [] }) {
    const chips = meta.filter(Boolean).slice(0, 4).map(item => `<span>${escapeHtml(item)}</span>`).join("");
    return `<label class="choice-card ${className} ${tone}">
        <input name="${escapeHtml(name)}" type="checkbox" value="${escapeHtml(value)}" ${checked ? "checked" : ""}>
        <span class="choice-check">${icon("check")}</span>
        <span class="choice-symbol">${icon(iconName)}</span>
        <span class="choice-copy"><small>${escapeHtml(eyebrow)}</small><strong>${escapeHtml(title)}</strong><em>${escapeHtml(description)}</em></span>
        <span class="choice-meta">${chips}</span>
    </label>`;
}

function categoryTypeMeta(type) {
    return {
        live: { label: "Live", iconName: "stream", icon: "L", tone: "live" },
        movie: { label: "Films", iconName: "ticket", icon: "F", tone: "movie" },
        series: { label: "Séries", iconName: "server", icon: "S", tone: "series" }
    }[type] || { label: "Catalogue", iconName: "server", icon: "C", tone: "catalog" };
}

function addonEntitlementId(addon) {
    return `addon:${addon.id}`;
}

function normalizeAddonRef(value) {
    const raw = String(value || "").trim();
    if (!raw) return "";
    if (raw.startsWith("addon:")) return raw;
    const match = raw.match(/^addon-?(\d+)/);
    return match ? `addon:${match[1]}` : raw;
}

function connectorStatus(connector) {
    const value = connector.statusPath.reduce((current, key) => current?.[key], state.integrations || {});
    if (!value) return { configured: false, label: "Statut inconnu" };
    const configured = Boolean(value[connector.configuredKey]);
    if (configured) return { configured, label: "Configuré" };
    if (value.enabled === false) return { configured, label: "Désactivé" };
    return { configured, label: "Incomplet" };
}

function typeLabel(type) {
    return { live: "Live", movie: "Films", series: "Séries" }[type] || type;
}

function field(name, label, value = "", required = false, type = "text", step = "") {
    return `<label class="admin-field"><span>${label}</span><input name="${name}" type="${type}" value="${escapeHtml(value ?? "")}" ${required ? "required" : ""} ${step ? `step="${step}"` : ""}></label>`;
}
function selectField(name, label, options, selected) {
    return `<label class="admin-field"><span>${label}</span><select name="${name}">${options.map(option => `<option value="${option}" ${option === selected ? "selected" : ""}>${option}</option>`).join("")}</select></label>`;
}
function textareaField(name, label, value = "") {
    return `<label class="admin-field"><span>${label}</span><textarea name="${name}">${escapeHtml(value)}</textarea></label>`;
}
function checkField(name, label, checked) {
    return `<label class="checkbox-field"><input name="${name}" type="checkbox" ${checked ? "checked" : ""}> ${label}</label>`;
}
function toLocalDateTime(value) {
    if (!value) return "";
    const date = new Date(value);
    return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}
function closeModal() {
    el.modal.hidden = true;
    delete el.modal.dataset.modalType;
    el.dynamicForm.onsubmit = null;
}

async function saveAccount(form, id) {
    const payload = {
        name: form.get("name"), type: form.get("type"), baseUrl: form.get("baseUrl"),
        playlistUrl: form.get("playlistUrl"), username: form.get("username"), password: form.get("password"),
        maxStreams: Number(form.get("maxStreams") || 0), active: form.has("active"),
        expiresAt: form.get("expiresAt") ? new Date(form.get("expiresAt")).toISOString() : null
    };
    await api(`/admin/accounts${id ? `/${id}` : ""}`, { method: id ? "PUT" : "POST", body: JSON.stringify(payload) });
}
async function savePlan(form, id) {
    const payload = Object.fromEntries(form.entries());
    ["priceMonthly","trialDays","billingPeriodDays","maxUsers","maxIptvAccounts","maxConcurrentStreams","storageGb"].forEach(key => payload[key] = Number(payload[key] || 0));
    payload.active = form.has("active");
    payload.entitlements = collectPlanEntitlements(form);
    await api(`/admin/billing/plans${id ? `/${id}` : ""}`, { method: id ? "PUT" : "POST", body: JSON.stringify(payload) });
}

function collectPlanEntitlements(form) {
    const entitlements = [];
    let priority = 0;
    if (form.has("entitlementAll")) {
        entitlements.push({ mode: "ALL", contentType: "all", label: "Catalogue complet", enabled: true, priority: priority++ });
    }
    form.getAll("entitlementTypes").forEach(type => {
        entitlements.push({ mode: "TYPE", contentType: type, label: typeLabel(type), enabled: true, priority: priority++ });
    });
    String(form.get("entitlementKeywords") || "")
        .split(",")
        .map(value => value.trim())
        .filter(Boolean)
        .forEach(keyword => {
            entitlements.push({ mode: "KEYWORD", contentType: "all", keyword, label: `Auto · ${keyword}`, enabled: true, priority: priority++ });
        });
    form.getAll("entitlementCategories").forEach(categoryId => {
        const category = state.catalogCategories.find(item => item.id === categoryId);
        entitlements.push({
            mode: "CATEGORY",
            contentType: category?.type || "all",
            categoryId,
            label: category ? `${String(category.type || "").toUpperCase()} · ${category.name}` : categoryId,
            enabled: true,
            priority: priority++
        });
    });
    form.getAll("entitlementAddons").forEach(addonRef => {
        const addonId = normalizeAddonRef(addonRef);
        const addon = state.addons.find(item => addonEntitlementId(item) === addonId);
        entitlements.push({
            mode: "ADDON",
            contentType: "all",
            categoryId: addonId,
            label: addon ? `Add-on · ${addon.name}` : `Add-on · ${addonId}`,
            enabled: true,
            priority: priority++
        });
    });
    form.getAll("entitlementConnectors").forEach(code => {
        const connector = CONNECTOR_OPTIONS.find(item => item.code === code);
        entitlements.push({
            mode: "CONNECTOR",
            contentType: "all",
            keyword: code,
            label: connector ? `Connecteur · ${connector.name}` : `Connecteur · ${code}`,
            enabled: true,
            priority: priority++
        });
    });
    return entitlements;
}
async function saveMethod(form, id) {
    await api(`/admin/billing/payment-methods${id ? `/${id}` : ""}`, { method: id ? "PUT" : "POST", body: JSON.stringify({ code: form.get("code"), name: form.get("name"), instructions: form.get("instructions"), active: form.has("active") }) });
}
async function saveUptime(form) {
    await api("/admin/ops/uptime-checks", { method: "POST", body: JSON.stringify({ name: form.get("name"), url: form.get("url"), method: form.get("method"), enabled: form.has("enabled") }) });
}
async function saveLegal(form, id) {
    await api(`/admin/legal${id ? `/${id}` : ""}`, {
        method: id ? "PUT" : "POST",
        body: JSON.stringify({
            type: form.get("type"),
            title: form.get("title"),
            content: form.get("content"),
            published: form.has("published")
        })
    });
}
async function saveUserCategories(form, id) {
    await api(`/admin/users/${id}/categories`, {
        method: "POST",
        body: JSON.stringify({ categories: form.getAll("categories") })
    });
}

async function saveAddon(form, id) {
    const payload = {
        manifestUrl: form.get("manifestUrl"),
        allowedStreamHosts: form.get("allowedStreamHosts"),
        licenseName: form.get("licenseName"),
        licenseUrl: form.get("licenseUrl"),
        adultContent: form.has("adultContent"),
        privateUse: form.has("privateUse")
    };
    await api(`/admin/addons${id ? `/${id}` : ""}`, {
        method: id ? "PUT" : "POST",
        body: JSON.stringify(payload)
    });
}

async function saveAddonAccess(form, id) {
    await api(`/admin/addons/${id}/access`, {
        method: "POST",
        body: JSON.stringify({
            userIds: form.getAll("userIds").map(Number)
        })
    });
}

async function addonAction(action, id) {
    const addon = state.addons.find(item => item.id === Number(id));
    if (!addon) return;
    if (action === "edit") return openModal("addon", addon);
    if (action === "access") return openModal("addonAccess", addon);
    if (action === "delete") {
        if (!confirm(`Supprimer l'add-on "${addon.name}" ?`)) return;
        await api(`/admin/addons/${id}`, { method: "DELETE" });
        showToast("Add-on supprimé.");
        return loadIntegrations();
    }
    await api(`/admin/addons/${id}/${action}`, { method: "POST" });
    showToast({
        approve: "Add-on approuvé et publié.",
        disable: "Add-on retiré du catalogue.",
        refresh: "Manifeste actualisé, nouvelle approbation requise."
    }[action]);
    await loadIntegrations();
}

function addonUserSelectField(addon) {
    const selected = new Set((addon?.allowedUserIds || []).map(Number));
    const users = (state.users || [])
        .filter(user => user.active && user.id !== addon?.ownerId);
    const options = users.map(user => `
        <option value="${user.id}" ${selected.has(Number(user.id)) ? "selected" : ""}>
            ${escapeHtml(user.name)} · ${escapeHtml(user.email)} · ${escapeHtml(String(user.role).replaceAll("_", " "))}
        </option>
    `).join("");
    return `<label class="admin-field">
        <span>Utilisateurs autorisés</span>
        <select class="category-select" name="userIds" multiple>${options}</select>
        <p class="field-hint">Maintenez Ctrl pour sélectionner plusieurs comptes. Une liste vide remet l'accès au propriétaire seul.</p>
    </label>`;
}

function categorySelectField(categories, selected) {
    return categoryCardsField(categories, selected, {
        name: "categories",
        title: "Rayons autorisés pour l'utilisateur",
        note: "Aucune carte cochée signifie que l'utilisateur suit les droits de son abonnement sans restriction manuelle."
    });
}

async function openCategoryModal(user) {
    await ensureCatalogCategories();
    openModal("categories", user);
}

async function openPlanModal(plan = null) {
    await ensurePlanAssets();
    openModal("plan", plan);
}

async function ensureCatalogCategories() {
    if (state.catalogCategories.length) return;
    const categoryGroups = await Promise.all(["live", "movie", "series"].map(type => api(`/catalog/categories?type=${type}`)));
    state.catalogCategories = categoryGroups.flat();
}

async function ensurePlanAssets() {
    await ensureCatalogCategories();
    const tasks = [];
    if (!state.addons.length && ["SUPER_ADMIN", "ADMIN"].includes(state.user?.role)) {
        tasks.push(api("/admin/addons").then(addons => { state.addons = addons; }));
    }
    if (!Object.keys(state.integrations || {}).length) {
        tasks.push(api("/admin/integrations/status").then(integrations => { state.integrations = integrations; }));
    }
    await Promise.all(tasks);
}

async function accountAction(action, id) {
    const account = state.accounts.find(item => item.id === Number(id));
    if (action === "edit") return openModal("account", account);
    if (action === "delete") {
        if (!confirm(`Archiver le compte "${account.name}" ? Son historique de sessions sera conservé.`)) return;
        await api(`/admin/accounts/${id}`, { method: "DELETE" });
        showToast("Compte archivé."); return loadIptv();
    }
    const routes = { status: `/admin/accounts/${id}/status`, cache: `/admin/accounts/${id}/refresh-cache`, limits: `/admin/accounts/${id}/sync-limits` };
    const method = action === "status" ? "GET" : "POST";
    const result = await api(routes[action], { method });
    showToast(action === "status" ? `État distant : ${result.remote}` : "Opération terminée.");
    await loadIptv();
}

async function runIptvAudit() {
    if (!el.runIptvAuditButton) return;
    el.runIptvAuditButton.disabled = true;
    try {
        const summary = await api("/admin/accounts/audit", { method: "POST" });
        showToast(`Audit termine: ${summary.alerts || 0} alerte(s), ${summary.emptyCatalogs || 0} catalogue(s) vide(s).`);
        await loadIptv();
    } finally {
        el.runIptvAuditButton.disabled = false;
    }
}

async function paymentAction(action, id) {
    let body;
    if (action === "reject") {
        const reason = prompt("Motif du rejet :");
        if (reason === null) return;
        body = JSON.stringify({ reason });
    }
    await api(`/admin/billing/payments/${id}/${action}`, { method: "POST", body });
    showToast(action === "verify" ? "Paiement validé." : "Paiement rejeté.");
    await loadBilling();
}

function filterCurrentView(query) {
    const normalized = query.trim().toLocaleLowerCase("fr");
    if (state.view === "iptv") {
        state.accountPage = 1;
        renderAccounts();
        document.querySelectorAll(`[data-admin-view="${state.view}"] #sessionsTable [data-searchable]`).forEach(item => {
            item.hidden = normalized && !item.dataset.searchable.toLocaleLowerCase("fr").includes(normalized);
        });
        return;
    }
    document.querySelectorAll(`[data-admin-view="${state.view}"] [data-searchable]`).forEach(item => {
        item.hidden = normalized && !item.dataset.searchable.toLocaleLowerCase("fr").includes(normalized);
    });
}

document.addEventListener("click", async event => {
    const viewButton = event.target.closest("[data-view], [data-jump]");
    if (viewButton) return switchView(viewButton.dataset.view || viewButton.dataset.jump);
    const modalButton = event.target.closest("[data-open-modal]");
    if (modalButton) return openModal(modalButton.dataset.openModal);
    if (event.target.closest("[data-close-admin-modal]")) return closeModal();
    const accountPageButton = event.target.closest("[data-account-page]");
    if (accountPageButton) {
        const target = accountPageButton.dataset.accountPage;
        const pageCount = Math.max(1, Math.ceil(filteredAccountCount() / state.accountPageSize));
        if (target === "prev") state.accountPage -= 1;
        else if (target === "next") state.accountPage += 1;
        else state.accountPage = Number(target) || 1;
        state.accountPage = Math.min(Math.max(1, state.accountPage), pageCount);
        renderAccounts();
        return;
    }
    const accountButton = event.target.closest("[data-account-action]");
    if (accountButton) try { await accountAction(accountButton.dataset.accountAction, accountButton.dataset.id); } catch (error) { showToast(error.message, true); }
    const addonButton = event.target.closest("[data-addon-action]");
    if (addonButton) try { await addonAction(addonButton.dataset.addonAction, addonButton.dataset.id); } catch (error) { showToast(error.message, true); }
    const closeSession = event.target.closest("[data-close-session]");
    if (closeSession && confirm("Fermer cette session de lecture ?")) {
        try { await api(`/admin/sessions/${closeSession.dataset.closeSession}`, { method: "DELETE" }); showToast("Session fermée."); await loadIptv(); } catch (error) { showToast(error.message, true); }
    }
    const orgButton = event.target.closest("[data-org-action]");
    if (orgButton) try { await api(`/admin/saas/customers/${orgButton.dataset.id}/${orgButton.dataset.orgAction}`, { method: "POST" }); showToast("Statut client actualisé."); await loadCustomers(); } catch (error) { showToast(error.message, true); }
    const toggle = event.target.closest("[data-user-toggle]");
    if (toggle) try { await api(`/admin/users/${toggle.dataset.userToggle}/toggle`, { method: "PATCH", body: JSON.stringify({ active: toggle.dataset.active !== "true" }) }); await loadCustomers(); } catch (error) { showToast(error.message, true); }
    const userCategories = event.target.closest("[data-user-categories]");
    if (userCategories) try { await openCategoryModal(state.users.find(user => user.id === Number(userCategories.dataset.userCategories))); } catch (error) { showToast(error.message, true); }
    const payment = event.target.closest("[data-payment-action]");
    if (payment) try { await paymentAction(payment.dataset.paymentAction, payment.dataset.id); } catch (error) { showToast(error.message, true); }
    const plan = event.target.closest("[data-plan-edit]");
    if (plan) try { await openPlanModal(state.plans.find(item => item.id === Number(plan.dataset.planEdit))); } catch (error) { showToast(error.message, true); }
    const method = event.target.closest("[data-method-edit]");
    if (method) openModal("method", state.methods.find(item => item.id === Number(method.dataset.methodEdit)));
    const legal = event.target.closest("[data-legal-edit]");
    if (legal) openModal("legal", state.legalDocuments.find(item => item.id === Number(legal.dataset.legalEdit)));
    const ticket = event.target.closest("[data-ticket-id], [data-ticket-open]");
    if (ticket) { if (state.view !== "support") await switchView("support"); await openTicket(ticket.dataset.ticketId || ticket.dataset.ticketOpen); }
    const check = event.target.closest("[data-run-check]");
    if (check) try { await api(`/admin/ops/uptime-checks/${check.dataset.runCheck}/run`, { method: "POST" }); showToast("Contrôle exécuté."); await loadOps(); } catch (error) { showToast(error.message, true); }
});

document.addEventListener("change", async event => {
    if (event.target === el.accountPageSize) {
        state.accountPageSize = Number(event.target.value) || 24;
        state.accountPage = 1;
        renderAccounts();
        return;
    }
    if (event.target.matches("[name='entitlementTypes'], [name='entitlementCategories'], [name='entitlementAddons']")) {
        const allAccess = el.dynamicForm.querySelector("[name='entitlementAll']");
        if (allAccess) allAccess.checked = false;
    }
    if (event.target.matches("[name='entitlementAll']") && event.target.checked) {
        el.dynamicForm.querySelectorAll("[name='entitlementTypes'], [name='entitlementCategories'], [name='entitlementAddons']")
            .forEach(input => { input.checked = false; });
        const keywords = el.dynamicForm.querySelector("[name='entitlementKeywords']");
        if (keywords) keywords.value = "";
    }
    const customerTab = event.target.closest("[data-customer-tab]");
    if (customerTab) {
        state.customerTab = customerTab.dataset.customerTab;
        document.querySelectorAll("[data-customer-tab]").forEach(button => button.classList.toggle("active", button === customerTab));
        renderCustomers(); return;
    }
    const billingTab = event.target.closest("[data-billing-tab]");
    if (billingTab) {
        state.billingTab = billingTab.dataset.billingTab;
        document.querySelectorAll("[data-billing-tab]").forEach(button => button.classList.toggle("active", button === billingTab));
        renderBilling(); return;
    }
    if (event.target.matches("[data-user-role]")) {
        try { await api(`/admin/users/${event.target.dataset.userRole}/role`, { method: "PATCH", body: JSON.stringify({ role: event.target.value }) }); showToast("Rôle mis à jour."); } catch (error) { showToast(error.message, true); }
    }
    if (event.target.matches("[data-ticket-status]")) {
        try { await api(`/admin/support/tickets/${event.target.dataset.ticketStatus}/status`, { method: "PATCH", body: JSON.stringify({ status: event.target.value }) }); showToast("Statut du ticket mis à jour."); await loadSupport(); } catch (error) { showToast(error.message, true); }
    }
    if (event.target.matches("[data-ticket-assignee]")) {
        try {
            await api(`/admin/support/tickets/${event.target.dataset.ticketAssignee}/assign`, {
                method: "PATCH",
                body: JSON.stringify({ userId: event.target.value ? Number(event.target.value) : null })
            });
            showToast("Assignation mise à jour.");
            await openTicket(event.target.dataset.ticketAssignee);
        } catch (error) { showToast(error.message, true); }
    }
});

document.addEventListener("input", event => {
    if (event.target.matches("[name='entitlementKeywords']") && event.target.value.trim()) {
        const allAccess = el.dynamicForm.querySelector("[name='entitlementAll']");
        if (allAccess) allAccess.checked = false;
    }
});

document.addEventListener("submit", async event => {
    const reply = event.target.closest("[data-ticket-reply]");
    if (!reply) return;
    event.preventDefault();
    const form = new FormData(reply);
    try {
        await api(`/admin/support/tickets/${reply.dataset.ticketReply}/reply`, { method: "POST", body: JSON.stringify({ body: form.get("body"), internal: form.has("internal") }) });
        showToast("Réponse envoyée."); await openTicket(reply.dataset.ticketReply);
    } catch (error) { showToast(error.message, true); }
});

document.querySelectorAll("[data-filter-group='accounts'] button").forEach(button => button.addEventListener("click", () => {
    state.accountFilter = button.dataset.filterValue;
    state.accountPage = 1;
    document.querySelectorAll("[data-filter-group='accounts'] button").forEach(item => item.classList.toggle("active", item === button));
    renderAccounts();
}));
document.querySelectorAll("[data-customer-tab]").forEach(button => button.addEventListener("click", () => button.dispatchEvent(new Event("change", { bubbles: true }))));
document.querySelectorAll("[data-billing-tab]").forEach(button => button.addEventListener("click", () => button.dispatchEvent(new Event("change", { bubbles: true }))));

el.loginForm.addEventListener("submit", login);
el.refresh.addEventListener("click", () => switchView(state.view));
el.runIptvAuditButton?.addEventListener("click", () => runIptvAudit().catch(error => showToast(error.message, true)));
el.search.addEventListener("input", () => filterCurrentView(el.search.value));
el.ticketStatusFilter.addEventListener("change", renderTickets);
el.smtpTestForm.addEventListener("submit", async event => {
    event.preventDefault();
    try {
        const result = await api("/admin/integrations/smtp/test", {
            method: "POST",
            body: JSON.stringify({ email: el.smtpTestEmail.value })
        });
        showToast(result.message, !result.success);
    } catch (error) {
        showToast(error.message, true);
    }
});
el.telegramTestButton.addEventListener("click", async () => {
    try {
        const result = await api("/admin/integrations/telegram/test", { method: "POST" });
        showToast(result.message, !result.success);
    } catch (error) {
        showToast(error.message, true);
    }
});
if (el.telegramAdminTestButton) {
    el.telegramAdminTestButton.addEventListener("click", async () => {
        try {
            const result = await api("/admin/integrations/telegram/admin/test", { method: "POST" });
            showToast(result.message, !result.success);
        } catch (error) {
            showToast(error.message, true);
        }
    });
}
el.templateTestButton.addEventListener("click", async () => {
    try {
        const result = await api("/admin/integrations/templates/test", {
            method: "POST",
            body: JSON.stringify({ email: el.smtpTestEmail.value })
        });
        showToast(result.message, !result.success);
    } catch (error) {
        showToast(error.message, true);
    }
});
el.billingAddButton.addEventListener("click", async () => {
    if (el.billingAddButton.dataset.modalType === "plan") {
        try { await openPlanModal(); } catch (error) { showToast(error.message, true); }
        return;
    }
    openModal(el.billingAddButton.dataset.modalType);
});
document.querySelector("#mobileMenu").addEventListener("click", () => el.sidebar.classList.toggle("open"));
document.querySelector("#adminLogout").addEventListener("click", async () => {
    try { await api("/auth/logout", { method: "POST" }); } catch {}
    localStorage.removeItem(TOKEN_KEY); state.token = null; state.user = null; showGate();
});

setInterval(() => {
    el.clock.textContent = new Intl.DateTimeFormat("fr-FR", { hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(new Date());
}, 1000);

authenticate();
