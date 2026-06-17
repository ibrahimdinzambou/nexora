const API_ROOT = "/api";
const TOKEN_KEY = "nexora_access_token";
const MOVIE_SORT_KEY = "nexora_movie_sort";
const SETTINGS_KEY = "nexora_profile_settings";
const NETWORK_RETRY_DELAYS = [700, 1800, 4000];
const STREAM_OPEN_RETRY_DELAYS = [900, 2200];
const IMAGE_RETRY_LIMIT = 2;
const PLAYER_RECOVERY_DELAY_MS = 6500;
const PLAYER_STARTUP_TIMEOUT_MS = 11000;
const PLAYER_MAX_RECOVERY_ATTEMPTS = 3;
const SEARCH_DEBOUNCE_MS = 550;
const MIN_REMOTE_SEARCH_LENGTH = 2;
const launchParams = new URLSearchParams(window.location.search);
const WATCH_REQUIRES_AUTH = launchParams.get("demo") !== "1";
const titleCollator = new Intl.Collator("fr", { sensitivity: "base", numeric: true });
const defaultSettings = {
    avatarTone: "gold",
    language: "fr",
    quality: "auto",
    subtitles: "off",
    autoplay: true,
    compactCards: false
};

const fallbackCatalog = [
    { id: "1001", name: "Africa News", type: "live", categoryId: "news", categoryName: "Actualités", image: "/assets/images/landscape-1.jpg" },
    { id: "1002", name: "World Sports", type: "live", categoryId: "sports", categoryName: "Sports", image: "/assets/images/landscape-2.jpg" },
    { id: "2001", name: "Action Demo Movie", type: "movie", categoryId: "movies-action", categoryName: "Films", image: "/assets/images/landscape-5.jpg" },
    { id: "3001", name: "Demo Series", type: "series", categoryId: "series-drama", categoryName: "Séries", image: "/assets/images/poster-2.jpg" }
];

const rowDefinitions = [
    { type: "live", title: "En direct maintenant", label: "Chaînes disponibles" },
    { type: "movie", title: "Films à ne pas manquer", label: "Sélection éditoriale" },
    { type: "series", title: "Séries du moment", label: "À regarder ensuite" }
];

const state = {
    token: localStorage.getItem(TOKEN_KEY),
    user: null,
    organization: null,
    subscription: null,
    catalog: [...fallbackCatalog],
    browseCatalog: [...fallbackCatalog],
    categories: [],
    languages: [],
    activeType: "all",
    activeCategory: "",
    activeAddonFilter: "",
    addonPages: 1,
    addonHasMore: false,
    activeLanguage: "",
    visibleCatalog: { live: 120, movie: 120, series: 120 },
    movieSort: localStorage.getItem(MOVIE_SORT_KEY) || "title-asc",
    query: "",
    authMode: "login",
    authRequired: WATCH_REQUIRES_AUTH,
    pendingAuthAction: null,
    profileSettings: loadProfileSettings(),
    twoFactorEmail: null,
    emailVerificationEmail: null,
    activeSessionToken: null,
    playerOpening: false,
    heartbeatId: null,
    mpegtsPlayer: null,
    playerHasStarted: false,
    playerErrorShown: false,
    playerRecoveryTimer: null,
    playerStartupTimer: null,
    playerRecoveryAttempts: 0,
    playerRecovering: false,
    playerLastProgressAt: 0,
    activePlayerItem: null,
    activeProxyUrl: null,
    activePlaybackMode: null,
    activePlaybackQuality: null,
    playerGeneration: 0,
    catalogLoading: false,
    catalogIsFallback: true,
    catalogRequestId: 0,
    catalogAbortController: null,
    searchResults: [],
    searchResultQuery: "",
    searchSuggestions: [],
    searchActiveIndex: -1,
    activeSeries: null,
    activeDetail: null,
    requestedPlan: launchParams.get("plan")
};

const elements = {
    topbar: document.querySelector("#topbar"),
    navLinks: [...document.querySelectorAll(".nav-link")],
    searchShell: document.querySelector("#searchShell"),
    searchInput: document.querySelector("#searchInput"),
    searchClear: document.querySelector("#searchClear"),
    searchPanel: document.querySelector("#searchSuggestions"),
    accountButton: document.querySelector("#accountButton"),
    accountLabel: document.querySelector("#accountLabel"),
    accountAvatar: document.querySelector("#accountAvatar"),
    heroPlay: document.querySelector("#heroPlay"),
    discoverButton: document.querySelector("#discoverButton"),
    catalogRows: document.querySelector("#catalogRows"),
    catalogStatus: document.querySelector("#catalogStatus"),
    catalogKicker: document.querySelector("#catalogKicker"),
    catalogTitle: document.querySelector("#catalogTitle"),
    genreList: document.querySelector("#genreList"),
    addonFilterControl: document.querySelector("#addonFilterControl"),
    addonFilterLabel: document.querySelector("#addonFilterLabel"),
    addonFilterSelect: document.querySelector("#addonFilterSelect"),
    languageFilter: document.querySelector("#languageFilter"),
    languageSelect: document.querySelector("#languageSelect"),
    movieSortControl: document.querySelector("#movieSortControl"),
    movieSort: document.querySelector("#movieSort"),
    emptyState: document.querySelector("#emptyState"),
    resetFilters: document.querySelector("#resetFilters"),
    authModal: document.querySelector("#authModal"),
    authForm: document.querySelector("#authForm"),
    authTabs: document.querySelector("#authTabs"),
    authTitle: document.querySelector("#authTitle"),
    authSubtitle: document.querySelector("#authSubtitle"),
    authSubmit: document.querySelector("#authSubmit"),
    authError: document.querySelector("#authError"),
    twoFactorField: document.querySelector("#twoFactorField"),
    profileModal: document.querySelector("#profileModal"),
    profileName: document.querySelector("#profileName"),
    profileEmail: document.querySelector("#profileEmail"),
    profileAvatar: document.querySelector("#profileAvatar"),
    profileOrganization: document.querySelector("#profileOrganization"),
    profilePlan: document.querySelector("#profilePlan"),
    profileStatus: document.querySelector("#profileStatus"),
    settingsNav: document.querySelector(".settings-nav"),
    profileSettingsForm: document.querySelector("#profileSettingsForm"),
    playbackSettingsForm: document.querySelector("#playbackSettingsForm"),
    passwordSettingsForm: document.querySelector("#passwordSettingsForm"),
    settingsName: document.querySelector("#settingsName"),
    settingsEmail: document.querySelector("#settingsEmail"),
    settingsLanguage: document.querySelector("#settingsLanguage"),
    settingsQuality: document.querySelector("#settingsQuality"),
    settingsSubtitles: document.querySelector("#settingsSubtitles"),
    settingsAutoplay: document.querySelector("#settingsAutoplay"),
    settingsCompactCards: document.querySelector("#settingsCompactCards"),
    settingsTwoFactor: document.querySelector("#settingsTwoFactor"),
    settingsPlan: document.querySelector("#settingsPlan"),
    settingsStatus: document.querySelector("#settingsStatus"),
    settingsPeriodEnd: document.querySelector("#settingsPeriodEnd"),
    settingsStreams: document.querySelector("#settingsStreams"),
    adminConsoleLink: document.querySelector("#adminConsoleLink"),
    logoutAllButton: document.querySelector("#logoutAllButton"),
    logoutButton: document.querySelector("#logoutButton"),
    footerAccountButton: document.querySelector("#footerAccountButton"),
    detailModal: document.querySelector("#detailModal"),
    detailBackdrop: document.querySelector("#detailBackdrop"),
    detailPoster: document.querySelector("#detailPoster"),
    detailCategory: document.querySelector("#detailCategory"),
    detailTitle: document.querySelector("#detailTitle"),
    detailMeta: document.querySelector("#detailMeta"),
    detailSummary: document.querySelector("#detailSummary"),
    detailFacts: document.querySelector("#detailFacts"),
    detailPlay: document.querySelector("#detailPlay"),
    detailLoading: document.querySelector("#detailLoading"),
    seriesModal: document.querySelector("#seriesModal"),
    seriesBackdrop: document.querySelector("#seriesBackdrop"),
    seriesPoster: document.querySelector("#seriesPoster"),
    seriesCategory: document.querySelector("#seriesCategory"),
    seriesTitle: document.querySelector("#seriesTitle"),
    seriesSummary: document.querySelector("#seriesSummary"),
    seriesSeasonCount: document.querySelector("#seriesSeasonCount"),
    seriesEpisodeCount: document.querySelector("#seriesEpisodeCount"),
    seriesRating: document.querySelector("#seriesRating"),
    seriesRelease: document.querySelector("#seriesRelease"),
    seriesCredits: document.querySelector("#seriesCredits"),
    seriesLoading: document.querySelector("#seriesLoading"),
    seriesContent: document.querySelector("#seriesContent"),
    seriesSeasonSelect: document.querySelector("#seriesSeasonSelect"),
    seasonTitle: document.querySelector("#seasonTitle"),
    episodeList: document.querySelector("#episodeList"),
    seriesError: document.querySelector("#seriesError"),
    playerModal: document.querySelector("#playerModal"),
    streamPlayer: document.querySelector("#streamPlayer"),
    playerPlaceholder: document.querySelector("#playerPlaceholder"),
    playerTitle: document.querySelector("#playerTitle"),
    playerBadge: document.querySelector("#playerBadge"),
    playerQuality: document.querySelector("#playerQuality"),
    playerMessage: document.querySelector("#playerMessage"),
    toast: document.querySelector("#toast")
};

async function api(path, options = {}) {
    const headers = new Headers(options.headers || {});
    headers.set("Accept", "application/json");

    if (options.body && !(options.body instanceof FormData)) {
        headers.set("Content-Type", "application/json");
    }
    if (state.token) {
        headers.set("Authorization", `Bearer ${state.token}`);
    }

    const response = await fetchWithRetry(`${API_ROOT}${path}`, { ...options, headers });
    const body = await response.json().catch(() => ({}));

    if (!response.ok || body.success === false) {
        const error = new Error(body.message || "Une erreur est survenue.");
        error.code = body.code;
        error.status = response.status;
        throw error;
    }

    return body.data;
}

async function fetchWithRetry(url, options) {
    const method = String(options.method || "GET").toUpperCase();
    const retryDelays = options.retryTransient === true
        ? STREAM_OPEN_RETRY_DELAYS
        : NETWORK_RETRY_DELAYS;
    const canRetry = (method === "GET" && !options.body) || options.retryTransient === true;
    const { retryTransient, ...fetchOptions } = options;

    for (let attempt = 0; ; attempt += 1) {
        try {
            const response = await fetch(url, fetchOptions);
            const transientStatus = [502, 503, 504].includes(response.status);
            if (!canRetry || !transientStatus || attempt >= retryDelays.length) {
                return response;
            }
        } catch (error) {
            const aborted = error?.name === "AbortError" || fetchOptions.signal?.aborted;
            if (!canRetry || aborted || attempt >= retryDelays.length) {
                throw error;
            }
        }
        await delay(retryDelays[attempt]);
    }
}

function delay(milliseconds) {
    return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}

function retryCatalogImage(image, fallback) {
    const originalSource = image.dataset.originalSource || image.getAttribute("src") || "";
    image.dataset.originalSource = originalSource;
    const sourceUrl = new URL(originalSource, window.location.origin);
    const isCatalogProxy = sourceUrl.pathname.startsWith("/api/catalog/images/");
    const retryCount = Number(image.dataset.retryCount || 0);

    if (isCatalogProxy && retryCount < IMAGE_RETRY_LIMIT) {
        const nextRetry = retryCount + 1;
        image.dataset.retryCount = String(nextRetry);
        window.setTimeout(() => {
            if (!image.isConnected) return;
            const retryUrl = new URL(originalSource, window.location.origin);
            retryUrl.searchParams.set("_retry", `${Date.now()}-${nextRetry}`);
            image.src = retryUrl.href;
        }, NETWORK_RETRY_DELAYS[Math.min(retryCount, NETWORK_RETRY_DELAYS.length - 1)]);
        return;
    }

    image.onerror = null;
    image.src = fallback;
}

function setResilientImage(image, source, fallback) {
    const safeSource = normalizeImageSource(source, fallback);
    image.dataset.originalSource = safeSource;
    image.dataset.retryCount = "0";
    image.onerror = () => retryCatalogImage(image, fallback);
    image.src = safeSource;
}

function normalizeImageSource(source, fallback = "/assets/images/landscape-1.jpg") {
    const value = String(source || "").trim();
    if (!value) return fallback;
    if (/^(?:file:|[a-z]:[\\/]|\\\\)/i.test(value)) return fallback;

    try {
        const parsed = new URL(value, window.location.origin);
        if (!["http:", "https:"].includes(parsed.protocol)) return fallback;
        return parsed.href;
    } catch {
        return fallback;
    }
}

function loadProfileSettings() {
    try {
        return { ...defaultSettings, ...JSON.parse(localStorage.getItem(SETTINGS_KEY) || "{}") };
    } catch {
        return { ...defaultSettings };
    }
}

function saveProfileSettings(nextSettings) {
    state.profileSettings = { ...state.profileSettings, ...nextSettings };
    localStorage.setItem(SETTINGS_KEY, JSON.stringify(state.profileSettings));
    applyProfileSettings();
}

function avatarColor(tone) {
    return {
        gold: "#e7c36d",
        red: "#e85d5d",
        green: "#4fbf88",
        blue: "#4f8fbf"
    }[tone] || "#e7c36d";
}

function applyProfileSettings() {
    const color = avatarColor(state.profileSettings.avatarTone);
    [elements.accountAvatar, elements.profileAvatar].forEach((avatar) => {
        if (!avatar) return;
        avatar.style.background = color;
    });
    document.body.classList.toggle("compact-catalog", Boolean(state.profileSettings.compactCards));
}

function fillSettingsForms() {
    if (!state.user) return;
    elements.settingsName.value = state.user.name || "";
    elements.settingsEmail.value = state.user.email || "";
    elements.settingsLanguage.value = state.profileSettings.language;
    elements.settingsQuality.value = state.profileSettings.quality;
    elements.settingsSubtitles.value = state.profileSettings.subtitles;
    elements.settingsAutoplay.checked = Boolean(state.profileSettings.autoplay);
    elements.settingsCompactCards.checked = Boolean(state.profileSettings.compactCards);
    elements.settingsTwoFactor.checked = Boolean(state.user.twoFactorEnabled);

    const tone = state.profileSettings.avatarTone;
    const toneInput = elements.profileSettingsForm.querySelector(`[name="avatarTone"][value="${tone}"]`);
    if (toneInput) toneInput.checked = true;
}

function formatAccountDate(value) {
    if (!value) return "—";
    return new Intl.DateTimeFormat("fr", { dateStyle: "medium" }).format(new Date(value));
}

function renderBillingSettings() {
    const plan = state.subscription?.plan;
    elements.settingsPlan.textContent = plan?.name || "Aucune formule";
    elements.settingsStatus.textContent = statusLabel(state.subscription?.status);
    elements.settingsPeriodEnd.textContent = formatAccountDate(state.subscription?.currentPeriodEnd);
    elements.settingsStreams.textContent = plan?.maxConcurrentStreams
        ? `${plan.maxConcurrentStreams} simultané${plan.maxConcurrentStreams > 1 ? "s" : ""} par utilisateur`
        : "—";
}

function normalizeItem(item, type, index) {
    const fallback = fallbackCatalog.filter((entry) => entry.type === type);
    const matched = fallback.find((entry) => entry.id === String(item.id)) || fallback[index % fallback.length];
    const itemType = ["live", "movie", "series"].includes(item.type) ? item.type : type;

    return {
        ...matched,
        ...item,
        id: String(item.id),
        type: itemType,
        categoryName: item.categoryName || matched?.categoryName || typeLabel(itemType),
        image: normalizeImageSource(
            item.poster || item.logo,
            matched?.image || "/assets/images/landscape-1.jpg"
        )
    };
}

async function loadCatalog() {
    const requestId = ++state.catalogRequestId;
    if (state.catalogAbortController) {
        state.catalogAbortController.abort();
        state.catalogAbortController = null;
    }

    if (!state.token) {
        state.catalogLoading = false;
        state.catalogIsFallback = true;
        state.categories = [];
        state.languages = [];
        state.browseCatalog = [...fallbackCatalog];
        state.catalog = [...fallbackCatalog];
        state.searchResults = [];
        state.searchResultQuery = "";
        updateCatalogStatus();
        renderCategories();
        renderAddonFilter();
        renderLanguageFilter();
        renderCatalog();
        renderSearchSuggestions();
        return;
    }

    const query = state.query.trim();
    const abortController = new AbortController();
    state.catalogAbortController = abortController;
    state.catalogLoading = true;
    updateCatalogStatus();
    renderSearchSuggestions();

    if (!query) {
        try {
            const [categories, languages] = await Promise.all([
                api("/v1/catalog/categories", { signal: abortController.signal }),
                api("/catalog/languages", { signal: abortController.signal })
            ]);
            if (requestId !== state.catalogRequestId) return;
            state.categories = categories || [];
            state.languages = languages || [];
            renderCategories();
            renderAddonFilter();
            renderLanguageFilter();
        } catch {
            if (requestId !== state.catalogRequestId) return;
            state.categories = [];
            state.languages = [];
            renderAddonFilter();
            renderLanguageFilter();
        }
    }

    try {
        const types = query || state.activeType === "all"
            ? ["live", "movie", "series"]
            : [state.activeType];
        const resultSets = await Promise.all(
            types.map((type) => {
                const movieLibrary = !query && state.activeType === "movie" && type === "movie";
                const requestedLimit = query
                    ? 240
                    : Math.max(state.visibleCatalog[type] || 120, movieLibrary ? 240 : 120);
                const params = new URLSearchParams({
                    type,
                    limit: String(requestedLimit)
                });
                if (query) params.set("q", query);
                if (!query && state.activeCategory) params.set("categoryId", state.activeCategory);
                if (!query && state.activeAddonFilter) params.set("addonFilter", state.activeAddonFilter);
                params.set("addonPages", query ? "3" : String(state.addonPages));
                if (state.activeLanguage) params.set("language", state.activeLanguage);
                if (movieLibrary) params.set("sort", state.movieSort);
                return api(`/catalog/items?${params}`, { signal: abortController.signal });
            })
        );
        if (requestId !== state.catalogRequestId) return;

        const apiItems = resultSets.flatMap((items, typeIndex) => {
            const type = types[typeIndex];
            return (items || []).map((item, index) => normalizeItem(item, type, index));
        }).filter((item) => types.includes(item.type));

        state.catalog = apiItems;
        const activeCategory = state.categories.find((category) => category.id === state.activeCategory);
        const activeAddonItems = apiItems.filter((item) => item.categoryId === state.activeCategory);
        state.addonHasMore = Boolean(
            !query
            && isAsaAddon(activeCategory)
            && activeAddonItems.length >= state.addonPages * 24
        );
        state.catalogIsFallback = false;
        if (query) {
            state.searchResults = apiItems;
            state.searchResultQuery = normalizeSearchText(query);
        } else {
            state.browseCatalog = apiItems;
            state.searchResults = [];
            state.searchResultQuery = "";
        }
        renderCatalog();
        renderSearchSuggestions();
    } catch (error) {
        if (requestId !== state.catalogRequestId) return;
        if (error?.name === "AbortError") return;
        if (error.status === 401) {
            clearSession();
        }
        state.catalogIsFallback = true;
        state.browseCatalog = [...fallbackCatalog];
        state.catalog = [...fallbackCatalog];
        state.searchResults = [];
        state.searchResultQuery = "";
        renderCatalog();
        renderSearchSuggestions();
        showToast("Le catalogue de démonstration reste disponible.", true);
    } finally {
        if (requestId !== state.catalogRequestId) return;
        if (state.catalogAbortController === abortController) {
            state.catalogAbortController = null;
        }
        state.catalogLoading = false;
        updateCatalogStatus();
        renderSearchSuggestions();
    }
}

function renderCategories() {
    const categories = state.categories.length
        ? state.categories
        : [
            { id: "news", name: "Actualités", type: "live" },
            { id: "sports", name: "Sports", type: "live" },
            { id: "movies-action", name: "Action", type: "movie" },
            { id: "series-drama", name: "Drame", type: "series" }
        ];

    const visible = categories.filter((category) => (
        !category.searchOnly
        && (state.activeType === "all" || category.type === state.activeType)
    ));

    elements.genreList.innerHTML = [
        `<button class="genre-chip ${state.activeCategory ? "" : "active"}" type="button" data-category="">Tout</button>`,
        ...visible.map((category) => (
            `<button class="genre-chip ${state.activeCategory === category.id ? "active" : ""} ${isAsaAddon(category) ? "asa-category" : ""}" type="button" data-category="${escapeHtml(category.id)}">
                ${isAsaAddon(category) ? `<span class="addon-badge addon-badge-inline">ASA</span>` : ""}
                <span>${escapeHtml(category.name)}</span>
            </button>`
        ))
    ].join("");
}

function renderAddonFilter() {
    const category = state.categories.find((item) => item.id === state.activeCategory);
    const options = Array.isArray(category?.filterOptions) ? category.filterOptions : [];
    const visible = isAsaAddon(category) && options.length > 0;
    elements.addonFilterControl.hidden = !visible;
    if (!visible) {
        elements.addonFilterSelect.innerHTML = "";
        return;
    }
    if (!options.includes(state.activeAddonFilter)) {
        state.activeAddonFilter = options[0] || "";
    }
    elements.addonFilterLabel.textContent = {
        year: "Année",
        studio: "Studio",
        performer: "Interprète",
        tag: "Tag",
        quality: "Qualité"
    }[category.filterName] || "Filtre ASA";
    elements.addonFilterSelect.innerHTML = options.map((option) => (
        `<option value="${escapeHtml(option)}">${escapeHtml(option)}</option>`
    )).join("");
    elements.addonFilterSelect.value = state.activeAddonFilter;
    elements.addonFilterControl.classList.toggle("active", Boolean(state.activeAddonFilter));
}

function renderLanguageFilter() {
    const visible = state.languages.filter((language) => (
        state.activeType === "all"
        || !Array.isArray(language.types)
        || language.types.includes(state.activeType)
    ));
    if (state.activeLanguage && !visible.some((language) => language.id === state.activeLanguage)) {
        state.activeLanguage = "";
    }
    elements.languageSelect.innerHTML = [
        `<option value="">Toutes les langues</option>`,
        ...visible.map((language) => (
            `<option value="${escapeHtml(language.id)}">${escapeHtml(language.name)}</option>`
        ))
    ].join("");
    elements.languageSelect.value = state.activeLanguage;
    elements.languageFilter.classList.toggle("active", Boolean(state.activeLanguage));
}

function filteredCatalog() {
    const query = normalizeSearchText(state.query);
    const searching = Boolean(query);

    const filtered = state.catalog.filter((item) => {
        const matchesType = searching || state.activeType === "all" || item.type === state.activeType;
        const matchesCategory = searching || !state.activeCategory || item.categoryId === state.activeCategory;
        const matchesLanguage = !state.activeLanguage || item.language === state.activeLanguage;
        const matchesQuery = !query || normalizeSearchText(
            `${item.name} ${item.categoryName} ${item.languageName || ""} ${typeLabel(item.type)}`
        ).includes(query);
        return matchesType && matchesCategory && matchesLanguage && matchesQuery;
    });
    return state.activeType === "movie" && !searching
        ? [...filtered].sort(movieComparator(state.movieSort))
        : filtered;
}

function renderCatalog() {
    const items = filteredCatalog();
    const searching = Boolean(state.query.trim());
    renderMovieSort(searching);
    const definitions = rowDefinitions.filter((row) => (
        searching || state.activeType === "all" || row.type === state.activeType
    ));

    const rows = definitions.map((row) => {
        const rowItems = items.filter((item) => item.type === row.type);
        if (!rowItems.length) {
            return "";
        }

        const homePreview = !searching && state.activeType === "all";
        const visibleLimit = homePreview
            ? 12
            : searching
                ? Math.max(240, state.visibleCatalog[row.type])
                : state.visibleCatalog[row.type];
        const visibleItems = rowItems.slice(0, visibleLimit);
        const shelves = buildCatalogShelves(row, rowItems, visibleItems, searching)
            .map((shelf) => ({ ...shelf, homePreview }));
        const remoteMore = state.addonHasMore
            && row.type === state.activeType
            && Boolean(state.activeCategory);
        const likelyRemoteMore = !searching
            && state.activeType === row.type
            && visibleItems.length >= visibleLimit;
        let loadMoreLabel = `Afficher plus · ${Math.max(0, rowItems.length - visibleItems.length)} restants`;
        if (likelyRemoteMore && visibleItems.length >= rowItems.length) {
            loadMoreLabel = "Afficher plus";
        }
        let loadMore = visibleItems.length < rowItems.length || remoteMore || likelyRemoteMore
            ? `<button class="button button-outline catalog-more" type="button" data-load-more="${row.type}">
                    ${loadMoreLabel}
               </button>`
            : "";
        if (homePreview) {
            loadMore = "";
        }
        if (remoteMore) {
            loadMore = loadMore.replace(/Afficher plus[^<]+/, "Charger la suite ASA");
        }
        return `${shelves.map(renderCatalogShelf).join("")}${loadMore}`;
    }).join("");

    const homeShowcase = !searching && state.activeType === "all"
        ? renderHomeShowcase(items)
        : "";
    elements.catalogRows.innerHTML = homeShowcase + rows;
    elements.emptyState.hidden = Boolean(rows);
    updateCatalogHeading(items.length);
}

function renderHomeShowcase(items) {
    const unique = uniqueCatalogItems(items);
    const posterItems = [
        ...unique.filter((item) => item.type === "series"),
        ...unique.filter((item) => item.type === "movie"),
        ...unique.filter((item) => item.type === "live")
    ].slice(0, 12);
    const orbitItems = [
        ...unique.filter((item) => item.type === "movie"),
        ...unique.filter((item) => item.type === "series"),
        ...unique.filter((item) => item.type === "live")
    ].slice(0, 14);

    if (!posterItems.length && !orbitItems.length) return "";

    return `
        <section class="watch-showcase" aria-labelledby="watchShowcaseTitle">
            <div class="watch-showcase-heading">
                <div>
                    <p class="section-kicker">SELECTION NEXORA</p>
                    <h3 id="watchShowcaseTitle">Nouvelles series sur Nexora</h3>
                </div>
                <div class="watch-showcase-controls">
                    <button class="row-arrow" type="button" data-row-scroll="-1" data-row-target="watchPosterRail" aria-label="Faire défiler la sélection vers la gauche">
                        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 18-6-6 6-6"></path></svg>
                    </button>
                    <button class="row-arrow" type="button" data-row-scroll="1" data-row-target="watchPosterRail" aria-label="Faire défiler la sélection vers la droite">
                        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6"></path></svg>
                    </button>
                </div>
                <button class="watch-showcase-more" type="button" data-filter-shortcut="series">
                    Voir plus
                    <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6"></path></svg>
                </button>
            </div>
            <div class="watch-poster-rail" id="watchPosterRail">
                ${posterItems.map(watchPosterCard).join("")}
            </div>
            <div class="watch-orbit-rail" id="watchOrbitRail">
                ${orbitItems.map(watchOrbitCard).join("")}
            </div>
        </section>
    `;
}

function uniqueCatalogItems(items) {
    return [...new Map(items.map((item) => [`${item.type}:${item.id}`, item])).values()];
}

function watchPosterCard(item) {
    const year = item.releaseYear || item.year || (item.type === "live" ? "Live" : "2026");
    const genre = item.categoryName || typeLabel(item.type);
    return `
        <button class="watch-poster-card" type="button" data-item-id="${escapeHtml(item.id)}" data-item-type="${item.type}" aria-label="${escapeHtml(`Ouvrir ${item.name}`)}">
            <img src="${escapeHtml(item.image)}" alt="" decoding="async" onerror="retryCatalogImage(this, '/assets/images/poster-1.jpg')">
            <span class="watch-poster-year">${escapeHtml(year)}</span>
            <strong>${escapeHtml(item.name)}</strong>
            <small><span>${escapeHtml(genre)}</span><b>${escapeHtml(typeLabel(item.type))}</b></small>
        </button>
    `;
}

function watchOrbitCard(item) {
    return `
        <button class="watch-orbit-card" type="button" data-item-id="${escapeHtml(item.id)}" data-item-type="${item.type}" aria-label="${escapeHtml(`Ouvrir ${item.name}`)}">
            <span><img src="${escapeHtml(item.image)}" alt="" decoding="async" onerror="retryCatalogImage(this, '/assets/images/landscape-1.jpg')"></span>
            <strong>${escapeHtml(item.name)}</strong>
            <small>${escapeHtml(typeLabel(item.type))}</small>
        </button>
    `;
}

function buildCatalogShelves(definition, allItems, visibleItems, searching) {
    const splitIntoShelves = !searching
        && state.activeType === definition.type
        && ["live", "movie", "series"].includes(definition.type);

    if (!splitIntoShelves) {
        return [{
            type: definition.type,
            title: definition.title,
            label: state.activeType === "all"
                ? `${definition.label} · ${visibleItems.length} à découvrir`
                : definition.label,
            items: visibleItems
        }];
    }

    const categoryOrder = new Map(
        state.categories
            .filter((category) => category.type === definition.type)
            .map((category, index) => [String(category.id), index])
    );
    const totals = new Map();
    allItems.forEach((item) => {
        const key = catalogGroupKey(item);
        totals.set(key, (totals.get(key) || 0) + 1);
    });

    const groups = new Map();
    visibleItems.forEach((item) => {
        const key = catalogGroupKey(item);
        if (!groups.has(key)) {
            groups.set(key, {
                key,
                title: item.categoryName || definition.title,
                items: []
            });
        }
        groups.get(key).items.push(item);
    });

    const orderedGroups = [...groups.values()].sort((left, right) => {
        const leftOrder = categoryOrder.get(left.key) ?? Number.MAX_SAFE_INTEGER;
        const rightOrder = categoryOrder.get(right.key) ?? Number.MAX_SAFE_INTEGER;
        return leftOrder - rightOrder || titleCollator.compare(left.title, right.title);
    });
    const onlyOneCategory = orderedGroups.length === 1;

    return orderedGroups.flatMap((group) => {
        const total = totals.get(group.key) || group.items.length;
        return chunkCatalogItems(group.items, 24).map((chunk, chunkIndex) => {
            const firstItem = chunkIndex * 24 + 1;
            const lastItem = firstItem + chunk.length - 1;
            const noun = definition.type === "live"
                ? "chaînes"
                : definition.type === "series"
                    ? "séries"
                    : "films";
            let title = group.title;
            if (definition.type === "live" && onlyOneCategory && chunkIndex === 0) {
                title = "En direct maintenant";
            } else if (chunkIndex > 0) {
                title = definition.type === "live" && onlyOneCategory
                    ? `La suite du direct · ${chunkIndex + 1}`
                    : definition.type === "live"
                        ? `Encore en direct · ${group.title} · ${chunkIndex + 1}`
                        : `Plus de ${group.title} · ${chunkIndex + 1}`;
            }
            return {
                type: definition.type,
                title,
                label: `${noun} ${firstItem}–${lastItem} sur ${total}`,
                items: chunk
            };
        });
    });
}

function catalogGroupKey(item) {
    return String(item.categoryId || normalizeSearchText(item.categoryName) || item.type);
}

function chunkCatalogItems(items, size) {
    const chunks = [];
    for (let index = 0; index < items.length; index += size) {
        chunks.push(items.slice(index, index + size));
    }
    return chunks;
}

function renderCatalogShelf(shelf, index) {
    const rowId = `catalog-row-${shelf.type}-${index}`;
    const posterLayout = ["movie", "series"].includes(shelf.type);
    const hasShortcut = Boolean(shelf.homePreview);
    const endCard = hasShortcut ? renderShelfMoreCard(shelf.type) : "";
    const trackItems = posterLayout
        ? shelf.items.map(posterMediaCard).join("")
        : shelf.items.map(mediaCard).join("");
    const controls = shelf.items.length > 4
        ? `
            <div class="row-navigation" aria-label="Navigation de la rangée">
                <button class="row-arrow" type="button" data-row-scroll="-1" data-row-target="${rowId}" aria-label="Faire défiler vers la gauche">
                    <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 18-6-6 6-6"></path></svg>
                </button>
                <button class="row-arrow" type="button" data-row-scroll="1" data-row-target="${rowId}" aria-label="Faire défiler vers la droite">
                    <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6"></path></svg>
                </button>
            </div>
        `
        : "";

    return `
        <section class="content-row content-row-${shelf.type}" aria-labelledby="${rowId}-title">
            <div class="row-heading">
                <div class="row-heading-copy">
                    <h3 id="${rowId}-title">${escapeHtml(shelf.title)}</h3>
                    <span>${escapeHtml(shelf.label)}</span>
                </div>
                ${hasShortcut ? `<button class="row-see-more" type="button" data-filter-shortcut="${shelf.type}">Voir plus</button>` : ""}
                ${controls}
            </div>
            <div class="card-track ${posterLayout ? "poster-card-track" : ""} card-track-${shelf.type}" id="${rowId}">
                ${trackItems}
                ${endCard}
            </div>
        </section>
    `;
}

function renderShelfMoreCard(type) {
    const labels = {
        live: ["Direct", "Toutes les chaînes"],
        movie: ["Films", "Voir la bibliothèque"],
        series: ["Séries", "Explorer les saisons"]
    };
    const [title, label] = labels[type] || labels.live;
    return `
        <button class="shelf-more-card" type="button" data-filter-shortcut="${type}" aria-label="Voir plus: ${escapeHtml(title)}">
            <span>${escapeHtml(label)}</span>
            <strong>${escapeHtml(title)}</strong>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6"></path></svg>
        </button>
    `;
}

function posterMediaCard(item) {
    const year = item.releaseYear || item.year || "";
    const meta = [
        item.type === "series" && item.episodeCount
            ? `${item.seasonCount || 1} saison${(item.seasonCount || 1) > 1 ? "s" : ""}`
            : year,
        item.categoryName || typeLabel(item.type)
    ].filter(Boolean).join(" · ");
    const addonBadge = isAsaAddon(item)
        ? `<span class="addon-badge addon-badge-card" aria-label="Contenu provenant de l'add-on ASA">ASA</span>`
        : "";
    const actionLabel = item.type === "series" ? "Voir les épisodes" : `Ouvrir ${item.name}`;

    return `
        <button class="media-card poster-media-card ${item.type}" type="button" data-item-id="${escapeHtml(item.id)}" data-item-type="${item.type}" aria-label="${escapeHtml(actionLabel)}">
            ${addonBadge}
            <img src="${escapeHtml(item.image)}" alt="" decoding="async" onerror="retryCatalogImage(this, '/assets/images/poster-1.jpg')">
            <span class="poster-card-copy">
                <strong>${escapeHtml(item.name)}</strong>
                <span>${escapeHtml(meta)}</span>
            </span>
        </button>
    `;
}

function mediaCard(item) {
    const label = item.type === "live"
        ? "En direct"
        : item.type === "series" && item.episodeCount
            ? `${item.seasonCount} saison${item.seasonCount > 1 ? "s" : ""} · ${item.episodeCount} épisodes`
            : item.type === "movie" && item.releaseYear
                ? `${item.categoryName} · ${item.releaseYear}`
            : item.categoryName;
    const live = item.type === "live" ? `<span class="live-label">LIVE</span>` : "";
    const addonBadge = isAsaAddon(item)
        ? `<span class="addon-badge addon-badge-card" aria-label="Contenu provenant de l'add-on ASA">ASA</span>`
        : "";
    const actionIcon = item.type === "series"
        ? `<svg viewBox="0 0 24 24"><path d="M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01"></path></svg>`
        : `<svg viewBox="0 0 24 24"><path d="m8 5 11 7-11 7z"></path></svg>`;
    const actionLabel = item.type === "series" ? "Voir les épisodes" : `Regarder ${item.name}`;

    return `
        <button class="media-card ${item.type === "series" ? "series" : ""}" type="button" data-item-id="${escapeHtml(item.id)}" data-item-type="${item.type}" aria-label="${escapeHtml(actionLabel)}">
            ${live}
            ${addonBadge}
            <img src="${escapeHtml(item.image)}" alt="" decoding="async" onerror="retryCatalogImage(this, '/assets/images/landscape-1.jpg')">
            <span class="card-content">
                <span class="card-copy">
                    <strong>${escapeHtml(item.name)}</strong>
                    <span>${escapeHtml(label)}</span>
                </span>
                <span class="card-play" aria-hidden="true">
                    ${actionIcon}
                </span>
            </span>
        </button>
    `;
}

function isAsaAddon(value) {
    return value?.addonKey === "org.adult.stremio.addon"
        || value?.source === "Adult Stremio Addon";
}

function movieComparator(sort) {
    const byTitle = (left, right) => titleCollator.compare(
        sortableMovieTitle(left.name),
        sortableMovieTitle(right.name)
    );
    if (sort === "title-desc") {
        return (left, right) => byTitle(right, left);
    }
    if (sort === "recent") {
        return (left, right) => (
            Number(right.releaseYear || 0) - Number(left.releaseYear || 0)
            || byTitle(left, right)
        );
    }
    if (sort === "category") {
        return (left, right) => (
            titleCollator.compare(left.categoryName || "", right.categoryName || "")
            || byTitle(left, right)
        );
    }
    return byTitle;
}

function sortableMovieTitle(value) {
    return normalizeSearchText(value)
        .replace(/[^a-z0-9]+/g, " ")
        .trim()
        .replace(/^(le|la|les|un|une|des|the|a|an)\s+/, "");
}

function movieSortLabel(sort) {
    return {
        "title-asc": "Titre A-Z",
        "title-desc": "Titre Z-A",
        recent: "Les plus récents",
        category: "Par genre"
    }[sort] || "Titre A-Z";
}

function renderMovieSort(searching = Boolean(state.query.trim())) {
    const visible = state.activeType === "movie" && !searching;
    elements.movieSortControl.hidden = !visible;
    elements.movieSort.value = state.movieSort;
}

function normalizeSearchText(value) {
    return String(value || "")
        .normalize("NFD")
        .replace(/[\u0300-\u036f]/g, "")
        .toLocaleLowerCase("fr")
        .trim();
}

function searchScore(item, query) {
    const title = normalizeSearchText(item.name);
    const category = normalizeSearchText(item.categoryName);
    const language = normalizeSearchText(item.languageName);
    const type = normalizeSearchText(typeLabel(item.type));
    const haystack = `${title} ${category} ${language} ${type}`;
    const terms = query.split(/\s+/).filter(Boolean);

    if (!terms.every((term) => haystack.includes(term))) return Number.POSITIVE_INFINITY;
    if (title === query) return 0;
    if (title.startsWith(query)) return 1;
    if (title.includes(query)) return 2;
    if (category.startsWith(query)) return 3;
    if (language.startsWith(query)) return 4;
    return 5;
}

function searchCandidateItems() {
    const query = normalizeSearchText(state.query);
    const hasRemoteResults = query && state.searchResultQuery === query;
    const source = hasRemoteResults
        ? state.searchResults
        : state.browseCatalog.length
            ? state.browseCatalog
            : state.catalog;
    const unique = [...new Map(source.map((item) => [`${item.type}:${item.id}`, item])).values()];

    if (!query) return unique.slice(0, 6);

    return unique
        .map((item, index) => ({ item, index, score: searchScore(item, query) }))
        .filter((entry) => Number.isFinite(entry.score))
        .sort((a, b) => a.score - b.score || a.index - b.index)
        .slice(0, 7)
        .map((entry) => entry.item);
}

function renderSearchSuggestions(forceOpen = false) {
    const focused = elements.searchShell.contains(document.activeElement);
    if (!forceOpen && !focused) {
        closeSearchSuggestions();
        return;
    }

    const query = state.query.trim();
    const items = searchCandidateItems();
    state.searchSuggestions = items;
    state.searchActiveIndex = -1;

    const heading = query ? "Suggestions pour vous" : "Tendances du catalogue";
    const status = state.catalogLoading && query
        ? "Recherche..."
        : `${items.length} proposition${items.length > 1 ? "s" : ""}`;
    const list = items.length
        ? `
            <ul class="search-result-list">
                ${items.map((item, index) => `
                    <li>
                        <button
                            class="search-result"
                            id="search-option-${index}"
                            type="button"
                            role="option"
                            aria-selected="false"
                            data-search-index="${index}"
                        >
                            <span class="search-result-image">
                                <img src="${escapeHtml(item.image)}" alt="" onerror="retryCatalogImage(this, '/assets/images/landscape-1.jpg')">
                            </span>
                            <span class="search-result-copy">
                                <strong>${escapeHtml(item.name)}</strong>
                                <span>${escapeHtml(typeLabel(item.type))} · ${escapeHtml(item.categoryName)}</span>
                            </span>
                            <span class="search-result-play" aria-hidden="true">
                                <svg viewBox="0 0 24 24"><path d="m8 5 11 7-11 7z"></path></svg>
                            </span>
                        </button>
                    </li>
                `).join("")}
            </ul>
        `
        : `
            <div class="search-empty">
                <strong>Aucun titre trouvé</strong>
                <span>Essayez un autre titre, une chaîne ou un genre.</span>
            </div>
        `;
    const showAll = query
        ? `<button class="search-show-all" type="button" data-search-all>Voir tous les résultats pour « ${escapeHtml(query)} »</button>`
        : "";

    elements.searchPanel.innerHTML = `
        <div class="search-panel-head">
            <span>${heading}</span>
            <span>${status}</span>
        </div>
        ${list}
        ${showAll}
    `;
    elements.searchPanel.hidden = false;
    elements.searchShell.classList.add("open");
    elements.searchInput.setAttribute("aria-expanded", "true");
    elements.searchInput.removeAttribute("aria-activedescendant");
}

function closeSearchSuggestions() {
    elements.searchPanel.hidden = true;
    elements.searchShell.classList.remove("open");
    elements.searchInput.setAttribute("aria-expanded", "false");
    elements.searchInput.removeAttribute("aria-activedescendant");
    state.searchActiveIndex = -1;
}

function setSearchActiveIndex(index) {
    const options = [...elements.searchPanel.querySelectorAll("[data-search-index]")];
    if (!options.length) return;

    const nextIndex = (index + options.length) % options.length;
    state.searchActiveIndex = nextIndex;
    options.forEach((option, optionIndex) => {
        const active = optionIndex === nextIndex;
        option.classList.toggle("active", active);
        option.setAttribute("aria-selected", String(active));
    });
    const activeOption = options[nextIndex];
    elements.searchInput.setAttribute("aria-activedescendant", activeOption.id);
    activeOption.scrollIntoView({ block: "nearest" });
}

function updateSearchChrome() {
    const hasQuery = Boolean(state.query.trim());
    elements.searchShell.classList.toggle("has-query", hasQuery);
    elements.searchClear.hidden = !hasQuery;
}

function resetSearchQuery(focusInput = false) {
    window.clearTimeout(searchTimer);
    state.query = "";
    state.searchResults = [];
    state.searchResultQuery = "";
    state.searchSuggestions = [];
    state.catalog = state.browseCatalog.length ? [...state.browseCatalog] : [...fallbackCatalog];
    elements.searchInput.value = "";
    updateSearchChrome();
    closeSearchSuggestions();
    renderCatalog();
    if (focusInput) elements.searchInput.focus();
}

async function commitSearch() {
    window.clearTimeout(searchTimer);
    closeSearchSuggestions();
    const normalizedQuery = normalizeSearchText(state.query);
    if (state.token && (!normalizedQuery || normalizedQuery.length >= MIN_REMOTE_SEARCH_LENGTH)) {
        await loadCatalog();
    } else {
        renderCatalog();
    }
    document.querySelector("#catalogue").scrollIntoView({ behavior: "smooth", block: "start" });
}

function updateCatalogHeading(resultCount) {
    const query = state.query.trim();
    if (query) {
        elements.catalogKicker.textContent = `RECHERCHE · ${resultCount} RÉSULTAT${resultCount > 1 ? "S" : ""}`;
        elements.catalogTitle.textContent = `Résultats pour « ${query} »`;
        elements.emptyState.querySelector("span").textContent = "0 résultat";
        elements.emptyState.querySelector("h3").textContent = `Aucun résultat pour « ${query} »`;
        elements.emptyState.querySelector("p").textContent = "Essayez un autre titre, une chaîne, un genre ou une catégorie.";
        return;
    }

    elements.catalogKicker.textContent = "À VOIR MAINTENANT";
    elements.catalogTitle.textContent = state.activeType === "movie"
        ? "Tous vos films, enfin bien rangés"
        : state.activeType === "live"
            ? "Toutes vos chaînes, en un coup d’œil"
            : "Le meilleur du catalogue";
    elements.emptyState.querySelector("span").textContent = "0 résultat";
    elements.emptyState.querySelector("h3").textContent = "Aucun programme à l’horizon";
    elements.emptyState.querySelector("p").textContent = "Essayez une autre recherche ou revenez à l’ensemble du catalogue.";
}

async function setFilter(type) {
    if (!state.token && state.authRequired) {
        requestLogin("Connectez-vous pour acceder au catalogue.");
        return;
    }
    resetSearchQuery(false);
    state.activeType = type;
    state.activeCategory = "";
    state.activeAddonFilter = "";
    state.addonPages = 1;
    state.addonHasMore = false;
    if (type !== "all") state.visibleCatalog[type] = 120;
    renderLanguageFilter();
    elements.navLinks.forEach((link) => link.classList.toggle("active", link.dataset.filter === type));
    renderCategories();
    renderAddonFilter();
    renderCatalog();
    await loadCatalog();
    document.querySelector("#catalogue").scrollIntoView({ behavior: "smooth", block: "start" });
}

function openModal(id) {
    const modal = document.getElementById(id);
    if (!modal) return;
    modal.hidden = false;
    document.body.classList.add("modal-open");
    window.setTimeout(() => modal.querySelector("input, button")?.focus(), 20);
}

function closeModal(id) {
    const modal = document.getElementById(id);
    if (!modal) return;
    if (id === "authModal" && state.authRequired && !state.user) {
        state.pendingAuthAction = null;
        window.location.href = "/";
        return;
    }
    modal.hidden = true;

    if (id === "playerModal") {
        state.activePlayerItem = null;
        stopPlayer();
    }

    if (![...document.querySelectorAll(".modal")].some((item) => !item.hidden)) {
        document.body.classList.remove("modal-open");
    }
}

function setAuthMode(mode) {
    state.authMode = mode;
    state.twoFactorEmail = null;
    state.emailVerificationEmail = null;
    elements.authError.hidden = true;
    elements.twoFactorField.hidden = true;
    elements.authTabs.hidden = false;
    elements.twoFactorField.querySelector("input").required = false;

    const register = mode === "register";
    document.querySelectorAll(".register-field").forEach((field) => {
        field.hidden = !register;
        field.querySelector("input").required = register;
    });

    elements.authTabs.querySelectorAll("button").forEach((button) => (
        button.classList.toggle("active", button.dataset.authMode === mode)
    ));
    elements.authTitle.textContent = register ? "Votre écran vous attend." : "Bon retour parmi nous.";
    elements.authSubtitle.textContent = register
        ? "Créez votre espace client pour démarrer votre expérience."
        : "Connectez-vous pour accéder au direct et à votre catalogue.";
    elements.authSubmit.firstChild.textContent = register ? "Créer mon compte " : "Se connecter ";
}

function requestLogin(message, afterLogin = null) {
    state.pendingAuthAction = afterLogin;
    if (message) {
        showToast(message);
    }
    setAuthMode("login");
    openModal("authModal");
}

async function submitAuth(event) {
    event.preventDefault();
    const formData = new FormData(elements.authForm);
    elements.authError.hidden = true;
    elements.authSubmit.classList.add("loading");

    try {
        let data;
        if (state.twoFactorEmail) {
            data = await api("/auth/2fa/verify", {
                method: "POST",
                body: JSON.stringify({
                    email: state.twoFactorEmail,
                    code: formData.get("code")
                })
            });
        } else if (state.emailVerificationEmail) {
            data = await api("/auth/email/verify", {
                method: "POST",
                body: JSON.stringify({
                    email: state.emailVerificationEmail,
                    code: formData.get("code")
                })
            });
        } else {
            const payload = {
                email: formData.get("email"),
                password: formData.get("password")
            };
            if (state.authMode === "register") {
                payload.name = formData.get("name");
                payload.organizationName = formData.get("organizationName");
            }

            data = await api(`/auth/${state.authMode}`, {
                method: "POST",
                body: JSON.stringify(payload)
            });
        }

        if (data.requiresEmailVerification) {
            state.emailVerificationEmail = data.email;
            elements.authTabs.hidden = true;
            elements.twoFactorField.hidden = false;
            elements.twoFactorField.querySelector("input").required = true;
            elements.authTitle.textContent = "Verification de votre email";
            elements.authSubtitle.textContent = `Saisissez le code OTP envoye a ${data.email}.`;
            elements.authSubmit.firstChild.textContent = "Verifier ";
            return;
        }

        if (data.requiresTwoFactor) {
            state.twoFactorEmail = data.email;
            elements.authTabs.hidden = true;
            elements.twoFactorField.hidden = false;
            elements.twoFactorField.querySelector("input").required = true;
            elements.authTitle.textContent = "Vérification en deux étapes";
            elements.authSubtitle.textContent = `Saisissez le code envoyé à ${data.email}.`;
            elements.authSubmit.firstChild.textContent = "Vérifier ";
            return;
        }

        await applySession(data);
        if (state.authMode === "register" && state.requestedPlan) {
            try {
                state.subscription = await api("/billing/change-plan", {
                    method: "POST",
                    body: JSON.stringify({ planCode: state.requestedPlan })
                });
                await refreshProfile();
                updateAccountUi();
            } catch (error) {
                showToast(error.message || "La formule pourra être choisie depuis votre espace.", true);
            }
        }
        const pendingAction = state.pendingAuthAction;
        state.pendingAuthAction = null;
        closeModal("authModal");
        elements.authForm.reset();
        setAuthMode("login");
        window.history.replaceState({}, "", "/watch.html");
        showToast(`Bienvenue ${state.user?.name || ""}.`);
        if (pendingAction) {
            try {
                await pendingAction();
            } catch (error) {
                showToast(error.message || "Action impossible apres connexion.", true);
            }
        }
    } catch (error) {
        elements.authError.textContent = error.message;
        elements.authError.hidden = false;
    } finally {
        elements.authSubmit.classList.remove("loading");
    }
}

async function applySession(data) {
    state.token = data.token;
    localStorage.setItem(TOKEN_KEY, state.token);
    state.user = data.user || null;
    state.organization = data.organization || null;
    state.subscription = data.subscription || null;
    await refreshProfile();
    updateAccountUi();
    await loadCatalog();
}

async function restoreSession() {
    if (!state.token) {
        updateAccountUi();
        if (state.authRequired) {
            requestLogin("Connectez-vous pour acceder a votre espace Nexora.");
            return;
        }
        await loadCatalog();
        return;
    }

    try {
        await refreshProfile();
        updateAccountUi();
        await loadCatalog();
    } catch {
        clearSession();
        if (state.authRequired) {
            requestLogin("Votre session a expire. Reconnectez-vous.");
            return;
        }
        await loadCatalog();
    }
}

function applyLaunchIntent() {
    const mode = launchParams.get("mode");
    if (mode === "register" && !state.token) {
        const signupParams = new URLSearchParams();
        const email = launchParams.get("email");
        const plan = launchParams.get("plan");
        if (email) signupParams.set("email", email);
        if (plan) signupParams.set("plan", plan);
        const query = signupParams.toString();
        window.location.replace(`/signup.html${query ? `?${query}` : ""}`);
        return;
    }
    if (mode !== "login" || state.token) return;
    setAuthMode(mode);
    const email = launchParams.get("email");
    if (email) {
        const emailInput = elements.authForm.querySelector('input[name="email"]');
        emailInput.value = email;
    }
    requestLogin(null);
}

async function refreshProfile() {
    const profile = await api("/auth/me");
    state.user = profile.user;
    state.organization = profile.organization;
    state.subscription = profile.subscription;
}

function updateAccountUi() {
    const connected = Boolean(state.user);
    const initial = state.user?.name?.trim().charAt(0).toUpperCase() || "N";
    elements.accountAvatar.textContent = initial;
    elements.accountLabel.textContent = connected ? state.user.name : "Connexion";
    elements.adminConsoleLink.hidden = !["SUPER_ADMIN", "ADMIN", "BILLING", "SUPPORT", "OPS"].includes(state.user?.role);
    applyProfileSettings();
    updateCatalogStatus();

    if (!connected) return;

    elements.profileAvatar.textContent = initial;
    elements.profileName.textContent = state.user.name;
    elements.profileEmail.textContent = state.user.email;
    elements.profileOrganization.textContent = state.organization?.name || "Espace personnel";
    elements.profilePlan.textContent = state.subscription?.plan?.name || "Aucune formule";
    elements.profileStatus.textContent = statusLabel(state.subscription?.status);
    fillSettingsForms();
    renderBillingSettings();
}

function updateCatalogStatus() {
    const connected = Boolean(state.user);
    elements.catalogStatus.classList.toggle("connected", connected);
    elements.catalogStatus.lastChild.textContent = state.catalogLoading
        ? " Synchronisation du catalogue..."
        : connected
            ? " Catalogue synchronisé"
            : " Mode découverte";
}

function clearSession() {
    state.token = null;
    state.user = null;
    state.organization = null;
    state.subscription = null;
    state.languages = [];
    state.activeLanguage = "";
    state.pendingAuthAction = null;
    localStorage.removeItem(TOKEN_KEY);
    updateAccountUi();
    renderLanguageFilter();
}

async function logout() {
    try {
        await api("/auth/logout", { method: "POST" });
    } catch {
        // The local session must still be cleared when the token has expired.
    }
    clearSession();
    closeModal("profileModal");
    state.catalogIsFallback = true;
    state.browseCatalog = [...fallbackCatalog];
    state.catalog = [...fallbackCatalog];
    resetSearchQuery(false);
    showToast("Vous êtes maintenant déconnecté.");
}

function switchSettingsTab(tab) {
    document.querySelectorAll("[data-settings-tab]").forEach((button) => {
        button.classList.toggle("active", button.dataset.settingsTab === tab);
    });
    document.querySelectorAll("[data-settings-panel]").forEach((panel) => {
        panel.classList.toggle("active", panel.dataset.settingsPanel === tab);
    });
}

async function submitProfileSettings(event) {
    event.preventDefault();
    const formData = new FormData(elements.profileSettingsForm);
    const localSettings = {
        avatarTone: formData.get("avatarTone") || "gold",
        language: formData.get("language") || "fr"
    };

    try {
        const user = await api("/auth/profile", {
            method: "PATCH",
            body: JSON.stringify({
                name: formData.get("name"),
                email: formData.get("email")
            })
        });
        state.user = user;
        saveProfileSettings(localSettings);
        updateAccountUi();
        showToast("Profil mis à jour.");
    } catch (error) {
        showToast(error.message, true);
    }
}

function submitPlaybackSettings(event) {
    event.preventDefault();
    const formData = new FormData(elements.playbackSettingsForm);
    saveProfileSettings({
        quality: formData.get("quality") || "auto",
        subtitles: formData.get("subtitles") || "off",
        autoplay: elements.settingsAutoplay.checked,
        compactCards: elements.settingsCompactCards.checked
    });
    showToast("Préférences de lecture enregistrées.");
}

async function submitPasswordSettings(event) {
    event.preventDefault();
    const formData = new FormData(elements.passwordSettingsForm);

    try {
        await api("/auth/password", {
            method: "POST",
            body: JSON.stringify({
                currentPassword: formData.get("currentPassword"),
                newPassword: formData.get("newPassword")
            })
        });
        elements.passwordSettingsForm.reset();
        showToast("Mot de passe modifié.");
    } catch (error) {
        showToast(error.message, true);
    }
}

async function toggleTwoFactor(event) {
    const enabled = event.target.checked;
    event.target.disabled = true;

    try {
        await api(`/auth/2fa/${enabled ? "enable" : "disable"}`, { method: "POST" });
        state.user.twoFactorEnabled = enabled;
        showToast(enabled ? "Validation en deux étapes activée." : "Validation en deux étapes désactivée.");
    } catch (error) {
        event.target.checked = !enabled;
        showToast(error.message, true);
    } finally {
        event.target.disabled = false;
    }
}

async function logoutAllDevices() {
    try {
        await api("/auth/logout-all", { method: "POST" });
    } catch (error) {
        showToast(error.message, true);
        return;
    }

    clearSession();
    closeModal("profileModal");
    state.catalogIsFallback = true;
    state.browseCatalog = [...fallbackCatalog];
    state.catalog = [...fallbackCatalog];
    resetSearchQuery(false);
    showToast("Tous les appareils ont été déconnectés.");
}

function handleAccountClick() {
    if (state.user) {
        updateAccountUi();
        openModal("profileModal");
    } else {
        requestLogin("Connectez-vous pour ouvrir votre espace client.", handleAccountClick);
    }
}

function selectCatalogItem(item) {
    if (item.type === "series" && !item.isEpisode) {
        openSeries(item);
        return;
    }
    if (item.type === "movie") {
        openMovieDetails(item);
        return;
    }
    playItem(item);
}

async function openMovieDetails(item) {
    if (!state.token) {
        requestLogin("Connectez-vous pour consulter la fiche du film.", () => openMovieDetails(item));
        return;
    }

    state.activeDetail = item;
    renderMovieDetails(item, true);
    openModal("detailModal");

    try {
        const details = await api(`/catalog/items/${encodeURIComponent(item.id)}`);
        state.activeDetail = {
            ...item,
            ...details,
            image: details.poster || item.image
        };
        renderMovieDetails(state.activeDetail, false);
    } catch (error) {
        renderMovieDetails(item, false);
        showToast(error.message || "La fiche détaillée est indisponible.", true);
    }
}

function renderMovieDetails(item, loading) {
    elements.detailTitle.textContent = item.name;
    elements.detailCategory.textContent = item.categoryName || "FILM";
    elements.detailSummary.textContent = item.summary
        || "Le fournisseur n’a pas encore renseigné de résumé pour ce film.";

    const metadata = [
        item.releaseYear || releaseYearFromDate(item.releaseDate),
        item.duration,
        item.rating ? `★ ${item.rating}/10` : "",
        item.ageRating
    ].filter(Boolean);
    elements.detailMeta.innerHTML = metadata.map((value) => (
        `<span>${escapeHtml(value)}</span>`
    )).join("");

    const facts = [
        ["Genres", joinedMetadata(item.genres)],
        ["Réalisation", joinedMetadata(item.directors)],
        ["Acteurs", joinedMetadata(item.cast, 10)],
        ["Pays", item.country]
    ].filter(([, value]) => value);
    elements.detailFacts.innerHTML = facts.map(([label, value]) => `
        <div>
            <dt>${escapeHtml(label)}</dt>
            <dd>${escapeHtml(value)}</dd>
        </div>
    `).join("");
    elements.detailFacts.hidden = !facts.length;
    setDetailArtwork(item.backdrop || item.image, item.poster || item.image);
    elements.detailLoading.hidden = !loading;
    const playable = item.streamAvailable !== false;
    elements.detailPlay.disabled = loading || !playable;
    elements.detailPlay.innerHTML = playable
        ? `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 5 11 7-11 7z"></path></svg> Regarder`
        : "Catalogue uniquement";
    if (!playable && item.streamUnavailableReason) {
        elements.detailSummary.textContent = item.streamUnavailableReason;
    }
}

function setDetailArtwork(backdropValue, posterValue) {
    const poster = posterValue || "/assets/images/poster-1.jpg";
    const backdrop = backdropValue || poster || "/assets/images/hero.jpg";
    setResilientImage(elements.detailPoster, poster, "/assets/images/poster-1.jpg");
    setResilientImage(elements.detailBackdrop, backdrop, "/assets/images/hero.jpg");
}

function joinedMetadata(value, limit = 6) {
    const values = Array.isArray(value)
        ? value
        : String(value || "").split(/\s*[,|]\s*/);
    return values.map((entry) => String(entry).trim()).filter(Boolean).slice(0, limit).join(", ");
}

function releaseYearFromDate(value) {
    return String(value || "").match(/(?:19|20)\d{2}/)?.[0] || "";
}

async function openSeries(item) {
    if (!state.token) {
        requestLogin("Connectez-vous pour consulter les episodes.", () => openSeries(item));
        return;
    }

    state.activeSeries = null;
    elements.seriesTitle.textContent = item.name;
    elements.seriesCategory.textContent = item.categoryName || "SÉRIE";
    elements.seriesSummary.textContent = "Organisation des saisons et épisodes.";
    elements.seriesSeasonCount.textContent = "— saison";
    elements.seriesEpisodeCount.textContent = "— épisode";
    elements.seriesRating.hidden = true;
    elements.seriesRelease.hidden = true;
    elements.seriesCredits.hidden = true;
    setSeriesArtwork(item.image);
    elements.seriesLoading.hidden = false;
    elements.seriesContent.hidden = true;
    elements.seriesError.hidden = true;
    openModal("seriesModal");

    try {
        const series = await api(
            `/catalog/series/${encodeURIComponent(item.id)}?title=${encodeURIComponent(item.name || "")}`
        );
        state.activeSeries = series;
        renderSeriesDetails(series, item.image);
    } catch (error) {
        elements.seriesLoading.hidden = true;
        elements.seriesContent.hidden = true;
        elements.seriesError.textContent = error.message || "Impossible de charger les épisodes de cette série.";
        elements.seriesError.hidden = false;
    }
}

function setSeriesArtwork(posterValue, backdropValue) {
    const poster = posterValue || "/assets/images/poster-2.jpg";
    const backdrop = backdropValue || poster || "/assets/images/hero.jpg";
    setResilientImage(elements.seriesPoster, poster, "/assets/images/poster-2.jpg");
    setResilientImage(elements.seriesBackdrop, backdrop, "/assets/images/hero.jpg");
}

function renderSeriesDetails(series, fallbackImage) {
    const seasons = series.seasons || [];
    const seasonCount = Number(series.seasonCount ?? seasons.length);
    const episodeCount = Number(series.episodeCount ?? seasons.reduce((total, season) => total + (season.episodes?.length || 0), 0));

    elements.seriesTitle.textContent = series.name;
    elements.seriesCategory.textContent = series.categoryName || "SÉRIE";
    elements.seriesSummary.textContent = series.summary || (episodeCount
        ? "Choisissez une saison, puis lancez l’épisode que vous souhaitez regarder."
        : "Aucun épisode n’est disponible pour le moment.");
    elements.seriesSeasonCount.textContent = `${seasonCount} saison${seasonCount > 1 ? "s" : ""}`;
    elements.seriesEpisodeCount.textContent = `${episodeCount} épisode${episodeCount > 1 ? "s" : ""}`;
    setOptionalText(elements.seriesRating, series.rating ? `★ ${series.rating}/10` : "");
    setOptionalText(elements.seriesRelease, series.releaseYear || releaseYearFromDate(series.releaseDate));
    const credits = [
        ["Genres", joinedMetadata(series.genres)],
        ["Création", joinedMetadata(series.directors)],
        ["Acteurs", joinedMetadata(series.cast, 8)]
    ].filter(([, value]) => value);
    elements.seriesCredits.innerHTML = credits.map(([label, value]) => `
        <div>
            <dt>${escapeHtml(label)}</dt>
            <dd>${escapeHtml(value)}</dd>
        </div>
    `).join("");
    elements.seriesCredits.hidden = !credits.length;
    setSeriesArtwork(series.poster || fallbackImage, series.backdrop);
    elements.seriesSeasonSelect.innerHTML = seasons.map((season) => (
        `<option value="${season.season}">${escapeHtml(season.name || `Saison ${season.season}`)} (${season.episodeCount ?? season.episodes?.length ?? 0})</option>`
    )).join("");
    elements.seriesLoading.hidden = true;
    elements.seriesError.hidden = true;
    elements.seriesContent.hidden = false;

    if (seasons.length) {
        renderSeriesSeason(seasons[0].season);
    } else {
        elements.seasonTitle.textContent = "Aucun épisode";
        elements.episodeList.innerHTML = "";
    }
}

function renderSeriesSeason(seasonNumber) {
    const series = state.activeSeries;
    if (!series) return;
    const season = (series.seasons || []).find((item) => String(item.season) === String(seasonNumber));
    if (!season) return;

    elements.seriesSeasonSelect.value = String(season.season);
    elements.seasonTitle.textContent = season.name || `Saison ${season.season}`;
    elements.episodeList.innerHTML = (season.episodes || []).map((episode) => {
        const image = normalizeImageSource(
            episode.poster || series.poster,
            "/assets/images/landscape-1.jpg"
        );
        const episodeNumber = Number(episode.episode || 1);
        const episodeMetadata = [
            `S${String(season.season).padStart(2, "0")} · E${String(episodeNumber).padStart(2, "0")}`,
            episode.duration,
            episode.rating ? `★ ${episode.rating}` : ""
        ].filter(Boolean).join(" · ");
        return `
            <button class="episode-row" type="button" data-episode-id="${escapeHtml(episode.id)}" aria-label="Regarder ${escapeHtml(episode.name)}">
                <span class="episode-number">${episodeNumber}</span>
                <span class="episode-thumb">
                    <img src="${escapeHtml(image)}" alt="" decoding="async" onerror="retryCatalogImage(this, '/assets/images/landscape-1.jpg')">
                </span>
                <span class="episode-copy">
                    <strong>${escapeHtml(episode.name || `Épisode ${episodeNumber}`)}</strong>
                    <span>${escapeHtml(episodeMetadata)}</span>
                    ${episode.summary ? `<p>${escapeHtml(episode.summary)}</p>` : ""}
                </span>
                <span class="episode-play" aria-hidden="true">
                    <svg viewBox="0 0 24 24"><path d="m8 5 11 7-11 7z"></path></svg>
                </span>
            </button>
        `;
    }).join("");
}

function setOptionalText(element, value) {
    element.textContent = value;
    element.hidden = !value;
}

function playSeriesEpisode(episodeId) {
    const series = state.activeSeries;
    if (!series) return;
    const episode = (series.seasons || [])
        .flatMap((season) => season.episodes || [])
        .find((item) => item.id === episodeId);
    if (!episode) return;
    if (episode.streamAvailable === false || series.streamAvailable === false) {
        showToast("Cet add-on fournit les informations sans flux de lecture.", true);
        return;
    }

    closeModal("seriesModal");
    playItem({
        ...episode,
        type: "series",
        isEpisode: true,
        name: `${series.name} · ${episode.name}`,
        categoryName: series.categoryName,
        image: normalizeImageSource(
            episode.poster || series.poster,
            "/assets/images/landscape-1.jpg"
        )
    });
}

async function playItem(item, options = {}) {
    if (!state.token) {
        requestLogin("Connectez-vous pour lancer un programme.", () => playItem(item, options));
        return;
    }

    if (state.catalogLoading && state.catalogIsFallback) {
        showToast("Le catalogue est encore en cours de synchronisation.", true);
        return;
    }

    if (state.catalogIsFallback) {
        showToast("Ce programme est un aperçu sans flux vidéo. Rechargez le catalogue.", true);
        return;
    }
    if (item.streamAvailable === false) {
        showToast("Cet add-on fournit un catalogue ou des métadonnées sans flux de lecture.", true);
        return;
    }
    if (state.playerOpening) return;
    state.playerOpening = true;
    setPlayerControlsBusy(true);
    state.playerErrorShown = false;
    state.playerRecoveryAttempts = 0;
    state.playerRecovering = false;
    clearPlayerRecoveryTimer();
    state.activePlayerItem = item;
    const requestedQuality = requestedPlaybackQuality(item, options);
    elements.playerQuality.value = requestedQuality;

    openModal("playerModal");
    elements.playerTitle.textContent = item.name;
    elements.playerBadge.textContent = item.type === "live" ? "DIRECT" : typeLabel(item.type).toUpperCase();
    state.playerHasStarted = false;
    setPlayerLoading("Préparation de votre programme...", "Connexion au flux sécurisé Nexora.");

    try {
        if (state.activeSessionToken) {
            await stopPlayer();
        }
        const stream = await api("/stream/open", {
            method: "POST",
            body: JSON.stringify({
                type: item.type,
                itemId: item.id,
                quality: requestedQuality
            }),
            retryTransient: true
        });
        await startStreamFromPayload(item, stream, requestedQuality);
    } catch (error) {
        detachPlayerMedia();
        showPlayerError(error.message || "Ce flux est momentanément indisponible.");
    } finally {
        state.playerOpening = false;
        setPlayerControlsBusy(false);
    }
}

async function changePlayerQuality(event) {
    const requestedQuality = event.target.value || "auto";
    const item = state.activePlayerItem;
    const previousQuality = state.activePlaybackQuality || state.profileSettings.quality || "auto";
    if (!item || !state.activeSessionToken) {
        saveProfileSettings({ quality: requestedQuality });
        return;
    }
    if (state.playerOpening || state.playerRecovering) {
        event.target.value = previousQuality;
        showToast("Patientez, le lecteur change deja de flux.");
        return;
    }

    saveProfileSettings({ quality: requestedQuality });
    state.playerOpening = true;
    state.playerErrorShown = false;
    state.playerRecoveryAttempts = 0;
    state.playerRecovering = false;
    clearPlayerRecoveryTimer();
    setPlayerControlsBusy(true);

    setPlayerLoading(
        `Passage en ${qualityLabel(requestedQuality)}...`,
        "Le flux redémarre avec le nouveau débit."
    );
    try {
        detachPlayerMedia();
        await settleDetachedPlayer();
        await restartActiveSessionWithQuality(item, requestedQuality);
    } catch (error) {
        if (requestedQuality !== "auto") {
            try {
                const fallback = await fallbackActiveSessionToAuto(
                    `${qualityLabel(requestedQuality)} ne demarre pas, retour en Auto.`
                );
                if (fallback) return;
            } catch (fallbackError) {
                elements.playerQuality.value = previousQuality;
                saveProfileSettings({ quality: previousQuality });
                showPlayerError(fallbackError.message || error.message || "Impossible de changer la qualite.");
                return;
            }
        }
        elements.playerQuality.value = previousQuality;
        saveProfileSettings({ quality: previousQuality });
        showPlayerError(error.message || "Impossible de changer la qualite.");
    } finally {
        state.playerOpening = false;
        setPlayerControlsBusy(false);
    }
}

function setPlayerControlsBusy(isBusy) {
    elements.playerQuality.disabled = Boolean(isBusy);
}

function requestedPlaybackQuality(item, options = {}) {
    if (options.quality) return options.quality;
    const savedQuality = state.profileSettings.quality || "auto";
    return item?.type === "live" ? savedQuality : "auto";
}

async function restartActiveSessionWithQuality(item, requestedQuality) {
    const stream = await api(`/stream/quality/${state.activeSessionToken}`, {
        method: "POST",
        body: JSON.stringify({ quality: requestedQuality }),
        retryTransient: true
    });
    await startStreamFromPayload(item, stream, requestedQuality);
}

async function fallbackActiveSessionToAuto(message) {
    if (!state.activePlayerItem || !state.activeSessionToken) {
        return false;
    }
    const previousQuality = state.activePlaybackQuality;
    setPlayerLoading("Retour en mode automatique...", message || "La qualite forcee ne demarre pas.");
    elements.playerQuality.value = "auto";
    saveProfileSettings({ quality: "auto" });
    detachPlayerMedia();
    await settleDetachedPlayer();
    await restartActiveSessionWithQuality(state.activePlayerItem, "auto");
    showToast(message || `${qualityLabel(previousQuality)} instable, retour en Auto.`);
    return true;
}

async function startStreamFromPayload(item, stream, requestedQuality) {
    applyStreamPayload(stream, requestedQuality);
    setPlayerLoading("Verification du flux...", "Nexora controle la source avant de lancer le lecteur.");
    const checkedStream = await preflightActiveStream(requestedQuality);
    startHeartbeat();
    setPlayerLoading("Ouverture du flux...", "Connexion directe a la source video.");
    await startStreamPlayback(
        item,
        checkedStream.proxyUrl || stream.proxyUrl,
        checkedStream.playbackMode || stream.playbackMode
    );
}

function applyStreamPayload(stream, requestedQuality) {
    state.activeSessionToken = stream.token || state.activeSessionToken;
    state.activePlaybackQuality = stream.quality || requestedQuality || "auto";
    elements.playerQuality.value = state.activePlaybackQuality;
    if (requestedQuality && state.activePlaybackQuality !== requestedQuality) {
        showToast(`${qualityLabel(requestedQuality)} indisponible, lecture en ${qualityLabel(state.activePlaybackQuality)}.`);
    }
}

async function preflightActiveStream(requestedQuality) {
    const stream = await api(`/stream/preflight/${state.activeSessionToken}`, {
        method: "POST",
        retryTransient: true
    });
    applyStreamPayload(stream, requestedQuality || stream.quality || "auto");
    return stream;
}

function settleDetachedPlayer() {
    return delay(180);
}

function qualityLabel(quality) {
    return {
        data: "480p",
        hd: "720p",
        fullhd: "1080p",
        uhd: "qualité originale",
        auto: "mode automatique"
    }[quality] || "mode automatique";
}

async function startStreamPlayback(item, proxyUrl, playbackMode) {
    const streamUrl = new URL(proxyUrl, window.location.origin).href;
    const generation = state.playerGeneration + 1;
    state.playerGeneration = generation;
    state.activeProxyUrl = proxyUrl;
    state.activePlaybackMode = playbackMode;
    state.playerLastProgressAt = Date.now();
    elements.streamPlayer.preload = "auto";
    schedulePlayerStartupWatchdog(generation);
    const useMpegTs = playbackMode === "mpegts";
    if (useMpegTs && window.mpegts?.getFeatureList().mseLivePlayback) {
        const sourceIsLive = item.type === "live";
        const player = window.mpegts.createPlayer({
            type: "mpegts",
            isLive: sourceIsLive,
            url: streamUrl
        }, {
            enableWorker: true,
            enableStashBuffer: !sourceIsLive,
            stashInitialSize: sourceIsLive ? 128 * 1024 : 384 * 1024,
            lazyLoad: false,
            lazyLoadMaxDuration: 0,
            lazyLoadRecoverDuration: 0,
            deferLoadAfterSourceOpen: false,
            autoCleanupSourceBuffer: true,
            autoCleanupMaxBackwardDuration: sourceIsLive ? 30 : 90,
            autoCleanupMinBackwardDuration: sourceIsLive ? 10 : 30,
            fixAudioTimestampGap: true,
            liveBufferLatencyChasing: sourceIsLive,
            liveBufferLatencyMaxLatency: 8,
            liveBufferLatencyMinRemain: 1
        });
        state.mpegtsPlayer = player;
        player.attachMediaElement(elements.streamPlayer);
        player.on(window.mpegts.Events.ERROR, (type, detail, info) => {
            if (generation !== state.playerGeneration) return;
            const status = mpegtsErrorStatus(info);
            if (isRecoverableMpegtsError(type, detail, info)) {
                schedulePlayerRecovery(
                    mpegtsErrorMessage(type, detail, info),
                    [429, 502, 503, 504].includes(status) || state.activePlaybackQuality !== "auto"
                );
                return;
            }
            showPlayerError(mpegtsErrorMessage(type, detail, info));
        });
        try {
            player.load();
            await player.play();
        } catch (error) {
            destroyMpegtsPlayer(player);
            throw error;
        }
        return;
    }

    elements.streamPlayer.src = streamUrl;
    await elements.streamPlayer.play();
}

function isRecoverableMpegtsError(type, detail, info) {
    const status = mpegtsErrorStatus(info);
    if ([401, 403, 404].includes(status)) return false;
    if (detail === "HttpStatusCodeInvalid" && status > 0 && status < 500) return false;
    return type !== "MediaError" || state.playerRecoveryAttempts === 0;
}

function mpegtsErrorStatus(info) {
    return Number(info?.code || info?.status || 0);
}

function setPlayerLoading(placeholderText, statusText) {
    if (!state.activeSessionToken && elements.playerModal.hidden) return;
    elements.playerPlaceholder.hidden = false;
    elements.playerPlaceholder.classList.remove("error");
    elements.playerPlaceholder.querySelector("p").textContent = placeholderText;
    elements.playerMessage.textContent = statusText;
}

function setPlayerPlaying() {
    state.playerHasStarted = true;
    state.playerLastProgressAt = Date.now();
    clearPlayerRecoveryTimer();
    clearPlayerStartupTimer();
    if (elements.streamPlayer.currentTime > 8) {
        state.playerRecoveryAttempts = 0;
    }
    elements.playerPlaceholder.classList.remove("error");
    elements.playerPlaceholder.hidden = true;
    elements.playerMessage.textContent = "Lecture en cours.";
}

function setPlayerBuffering(placeholderText, statusText) {
    if (!state.playerHasStarted) {
        setPlayerLoading(placeholderText, statusText);
        return;
    }

    elements.playerPlaceholder.hidden = true;
    elements.playerMessage.textContent = statusText;
}

function clearPlayerRecoveryTimer() {
    if (state.playerRecoveryTimer) {
        window.clearTimeout(state.playerRecoveryTimer);
        state.playerRecoveryTimer = null;
    }
}

function clearPlayerStartupTimer() {
    if (state.playerStartupTimer) {
        window.clearTimeout(state.playerStartupTimer);
        state.playerStartupTimer = null;
    }
}

function schedulePlayerStartupWatchdog(generation) {
    clearPlayerStartupTimer();
    state.playerStartupTimer = window.setTimeout(() => {
        if (generation !== state.playerGeneration
            || state.playerHasStarted
            || state.playerRecovering
            || state.playerErrorShown
            || !state.activeSessionToken) {
            return;
        }
        const quality = state.activePlaybackQuality || "auto";
        if (quality !== "auto") {
            recoverPlayer(`${qualityLabel(quality)} ne demarre pas, retour en Auto.`);
            return;
        }
        schedulePlayerRecovery("Le flux met trop de temps a demarrer.", true);
    }, PLAYER_STARTUP_TIMEOUT_MS);
}

function schedulePlayerRecovery(reason, immediate = false) {
    if (!state.activeSessionToken || state.playerRecovering || state.playerErrorShown) {
        return;
    }
    if (state.playerRecoveryTimer) {
        return;
    }
    const stalledFor = Date.now() - state.playerLastProgressAt;
    const delay = immediate
        ? 500
        : state.playerHasStarted && stalledFor > PLAYER_RECOVERY_DELAY_MS
        ? 900
        : PLAYER_RECOVERY_DELAY_MS;
    elements.playerMessage.textContent = reason || "Le flux ralentit, tentative de reprise...";
    state.playerRecoveryTimer = window.setTimeout(() => recoverPlayer(reason), delay);
}

async function recoverPlayer(reason) {
    clearPlayerRecoveryTimer();
    if (!state.activeSessionToken || state.playerErrorShown || state.playerRecovering) return;
    if (state.playerOpening) {
        state.playerRecoveryTimer = window.setTimeout(
            () => recoverPlayer(reason),
            500
        );
        return;
    }
    state.playerRecovering = true;
    state.playerRecoveryAttempts += 1;

    try {
        if (state.playerRecoveryAttempts > PLAYER_MAX_RECOVERY_ATTEMPTS) {
            showPlayerError("Le flux reste instable après plusieurs tentatives de reprise.");
            return;
        }

        setPlayerLoading(
            state.playerRecoveryAttempts > 1 ? "Bascule vers une autre source..." : "Reprise du flux...",
            reason || "Nexora tente de stabiliser la lecture."
        );

        if (state.activePlaybackQuality && state.activePlaybackQuality !== "auto") {
            const fallback = await fallbackActiveSessionToAuto(
                `${qualityLabel(state.activePlaybackQuality)} instable, retour en Auto.`
            );
            if (fallback) return;
        }

        if (state.playerRecoveryAttempts > 1) {
            const failover = await tryFailoverPlayer();
            if (failover) {
                await restartPlaybackFromPayload(failover);
                return;
            }
        }

        await restartCurrentPlayback();
    } catch (error) {
        if (state.playerRecoveryAttempts >= PLAYER_MAX_RECOVERY_ATTEMPTS) {
            showPlayerError(error.message || "Impossible de reprendre ce flux.");
            return;
        }
        state.playerRecoveryTimer = window.setTimeout(
            () => recoverPlayer(error.message || reason),
            PLAYER_RECOVERY_DELAY_MS
        );
    } finally {
        state.playerRecovering = false;
    }
}

async function tryFailoverPlayer() {
    if (!state.activeSessionToken) return null;
    try {
        return await api(`/stream/failover/${state.activeSessionToken}`, {
            method: "POST",
            retryTransient: true
        });
    } catch {
        return null;
    }
}

async function restartPlaybackFromPayload(stream) {
    applyStreamPayload(stream, stream.quality || state.activePlaybackQuality || "auto");
    setPlayerLoading("Verification du flux...", "Nexora controle la source de remplacement.");
    const checkedStream = await preflightActiveStream(stream.quality || state.activePlaybackQuality || "auto");
    startHeartbeat();
    await restartCurrentPlayback(
        checkedStream.proxyUrl || stream.proxyUrl,
        checkedStream.playbackMode || stream.playbackMode
    );
}

async function restartCurrentPlayback(proxyUrl = state.activeProxyUrl, playbackMode = state.activePlaybackMode) {
    if (!state.activePlayerItem || !proxyUrl || !playbackMode) {
        throw new Error("Session de lecture incomplète.");
    }
    detachPlayerMedia();
    state.playerHasStarted = false;
    state.playerLastProgressAt = Date.now();
    await startStreamPlayback(state.activePlayerItem, proxyUrl, playbackMode);
}

function detachPlayerMedia() {
    state.playerGeneration += 1;
    clearPlayerRecoveryTimer();
    clearPlayerStartupTimer();
    if (state.mpegtsPlayer) {
        destroyMpegtsPlayer(state.mpegtsPlayer);
        state.mpegtsPlayer = null;
    }
    elements.streamPlayer.pause();
    elements.streamPlayer.removeAttribute("src");
    elements.streamPlayer.load();
}

function destroyMpegtsPlayer(player) {
    try {
        player.pause();
        player.unload();
        player.detachMediaElement();
        player.destroy();
    } catch {
        // The player may already be detached after a network error.
    }
    if (state.mpegtsPlayer === player) {
        state.mpegtsPlayer = null;
    }
}

function showPlayerError(message) {
    if (state.playerErrorShown) return;
    state.playerErrorShown = true;
    clearPlayerRecoveryTimer();
    clearPlayerStartupTimer();
    elements.playerPlaceholder.hidden = false;
    elements.playerPlaceholder.classList.add("error");
    elements.playerPlaceholder.querySelector("p").textContent = message;
    elements.playerMessage.textContent = "Essayez une autre qualité ou une autre chaîne.";
    if (state.activeSessionToken) {
        window.setTimeout(() => stopPlayer(), 0);
    }
}

function mpegtsErrorMessage(type, detail, info) {
    const status = Number(info?.code || info?.status || 0);
    if (status === 503) {
        return "Ce flux est momentanément indisponible chez le fournisseur.";
    }
    if (status === 401 || status === 403) {
        return "Le fournisseur a refusé l’accès à ce flux.";
    }
    if (status === 404) {
        return "Ce flux n’existe plus chez le fournisseur.";
    }
    if (detail === "HttpStatusCodeInvalid") {
        return "Le fournisseur n’a pas pu ouvrir ce flux vidéo.";
    }
    if (type === "MediaError") {
        return "Le format vidéo de ce flux n’est pas pris en charge.";
    }
    return "La lecture MPEG-TS a été interrompue.";
}

function startHeartbeat() {
    stopHeartbeat();
    state.heartbeatId = window.setInterval(async () => {
        if (!state.activeSessionToken) return;
        try {
            await api(`/stream/heartbeat/${state.activeSessionToken}`, { method: "POST" });
        } catch {
            stopHeartbeat();
        }
    }, 30000);
}

function stopHeartbeat() {
    if (state.heartbeatId) {
        window.clearInterval(state.heartbeatId);
        state.heartbeatId = null;
    }
}

async function stopPlayer() {
    const token = state.activeSessionToken;
    state.activeSessionToken = null;
    state.playerHasStarted = false;
    state.playerRecoveryAttempts = 0;
    state.playerRecovering = false;
    state.activeProxyUrl = null;
    state.activePlaybackMode = null;
    state.activePlaybackQuality = null;
    stopHeartbeat();
    detachPlayerMedia();

    if (token && state.token) {
        try {
            await api(`/stream/close/${token}`, { method: "DELETE" });
        } catch {
            // Closing the visual player remains immediate even if the API is unavailable.
        }
    }
}

function releasePlayerSession() {
    const sessionToken = state.activeSessionToken;
    const accessToken = state.token;
    if (!sessionToken || !accessToken) return;

    state.activeSessionToken = null;
    state.playerRecovering = false;
    state.playerRecoveryAttempts = 0;
    state.activeProxyUrl = null;
    state.activePlaybackMode = null;
    state.activePlaybackQuality = null;
    clearPlayerRecoveryTimer();
    stopHeartbeat();
    fetch(`${API_ROOT}/stream/close/${encodeURIComponent(sessionToken)}`, {
        method: "DELETE",
        headers: {
            Accept: "application/json",
            Authorization: `Bearer ${accessToken}`
        },
        keepalive: true
    }).catch(() => {
        // The backend timeout cleanup remains the fallback if the browser aborts the request.
    });
}

let toastTimer;
function showToast(message, isError = false) {
    window.clearTimeout(toastTimer);
    elements.toast.textContent = message;
    elements.toast.classList.toggle("error", isError);
    elements.toast.hidden = false;
    toastTimer = window.setTimeout(() => {
        elements.toast.hidden = true;
    }, 3600);
}

function typeLabel(type) {
    return { live: "Direct", movie: "Film", series: "Série" }[type] || "Programme";
}

function statusLabel(status) {
    return {
        ACTIVE: "Actif",
        TRIALING: "Période d’essai",
        TRIAL: "Période d’essai",
        CANCELED: "Résilié",
        EXPIRED: "Expiré",
        PENDING: "En attente"
    }[status] || status || "Inactif";
}

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
}

window.addEventListener("scroll", () => {
    elements.topbar.classList.toggle("scrolled", window.scrollY > 35);
}, { passive: true });

window.addEventListener("pagehide", releasePlayerSession);

elements.navLinks.forEach((link) => {
    link.addEventListener("click", () => setFilter(link.dataset.filter));
});

let searchTimer;
elements.searchShell.addEventListener("click", (event) => {
    if (event.target.closest(".search-box") && !event.target.closest("#searchClear")) {
        elements.searchInput.focus();
    }
});

elements.searchInput.addEventListener("input", (event) => {
    state.query = event.target.value;
    const normalizedQuery = normalizeSearchText(state.query);
    state.searchResults = [];
    state.searchResultQuery = "";
    state.catalog = state.browseCatalog.length ? [...state.browseCatalog] : [...fallbackCatalog];
    window.clearTimeout(searchTimer);
    updateSearchChrome();
    renderCatalog();
    renderSearchSuggestions(true);

    if (!state.token || normalizedQuery.length < MIN_REMOTE_SEARCH_LENGTH) {
        return;
    }
    searchTimer = window.setTimeout(() => loadCatalog(), SEARCH_DEBOUNCE_MS);
});

elements.searchInput.addEventListener("focus", () => {
    updateSearchChrome();
    renderSearchSuggestions(true);
});

elements.searchInput.addEventListener("keydown", (event) => {
    if (event.key === "ArrowDown") {
        event.preventDefault();
        if (elements.searchPanel.hidden) renderSearchSuggestions(true);
        setSearchActiveIndex(state.searchActiveIndex + 1);
        return;
    }

    if (event.key === "ArrowUp") {
        event.preventDefault();
        if (elements.searchPanel.hidden) renderSearchSuggestions(true);
        setSearchActiveIndex(state.searchActiveIndex - 1);
        return;
    }

    if (event.key === "Enter") {
        event.preventDefault();
        if (state.searchActiveIndex >= 0) {
            const item = state.searchSuggestions[state.searchActiveIndex];
            if (item) {
                closeSearchSuggestions();
                selectCatalogItem(item);
            }
        } else if (state.query.trim()) {
            commitSearch();
        }
        return;
    }

    if (event.key === "Escape") {
        event.preventDefault();
        closeSearchSuggestions();
        elements.searchInput.blur();
    }
});

elements.searchClear.addEventListener("click", async () => {
    resetSearchQuery(true);
    renderSearchSuggestions(true);
    if (state.token) await loadCatalog();
});

elements.searchPanel.addEventListener("click", (event) => {
    const result = event.target.closest("[data-search-index]");
    if (result) {
        const item = state.searchSuggestions[Number(result.dataset.searchIndex)];
        if (item) {
            closeSearchSuggestions();
            selectCatalogItem(item);
        }
        return;
    }

    if (event.target.closest("[data-search-all]")) {
        commitSearch();
    }
});

elements.genreList.addEventListener("click", async (event) => {
    const chip = event.target.closest("[data-category]");
    if (!chip) return;
    resetSearchQuery(false);
    state.activeCategory = chip.dataset.category;
    const category = state.categories.find((item) => item.id === state.activeCategory);
    state.activeAddonFilter = category?.filterOptions?.[0] || "";
    state.addonPages = 1;
    state.addonHasMore = false;
    if (category) {
        state.activeType = category.type;
        state.visibleCatalog[category.type] = 120;
        elements.navLinks.forEach((link) => (
            link.classList.toggle("active", link.dataset.filter === category.type)
        ));
    }
    renderCategories();
    renderAddonFilter();
    renderLanguageFilter();
    await loadCatalog();
});

elements.addonFilterSelect.addEventListener("change", async (event) => {
    state.activeAddonFilter = event.target.value;
    state.addonPages = 1;
    state.addonHasMore = false;
    await loadCatalog();
});

elements.languageSelect.addEventListener("change", async (event) => {
    state.activeLanguage = event.target.value;
    state.activeCategory = "";
    state.activeAddonFilter = "";
    state.addonPages = 1;
    state.addonHasMore = false;
    renderCategories();
    renderAddonFilter();
    renderLanguageFilter();
    await loadCatalog();
});

elements.movieSort.addEventListener("change", async (event) => {
    state.movieSort = event.target.value;
    localStorage.setItem(MOVIE_SORT_KEY, state.movieSort);
    renderCatalog();
    if (state.token) {
        await loadCatalog();
    }
});

elements.catalogRows.addEventListener("click", async (event) => {
    const filterShortcut = event.target.closest("[data-filter-shortcut]");
    if (filterShortcut) {
        await setFilter(filterShortcut.dataset.filterShortcut || "all");
        return;
    }

    const rowScroll = event.target.closest("[data-row-scroll]");
    if (rowScroll) {
        const track = document.getElementById(rowScroll.dataset.rowTarget);
        if (track) {
            const direction = Number(rowScroll.dataset.rowScroll);
            const distance = Math.max(track.clientWidth * 0.82, 260);
            track.scrollBy({ left: direction * distance, behavior: "smooth" });
        }
        return;
    }

    const loadMore = event.target.closest("[data-load-more]");
    if (loadMore) {
        if (state.addonHasMore && state.activeCategory) {
            state.addonPages += 1;
            await loadCatalog();
            return;
        }
        state.visibleCatalog[loadMore.dataset.loadMore] += 240;
        await loadCatalog();
        return;
    }
    const card = event.target.closest("[data-item-id]");
    if (!card) return;
    const item = state.catalog.find((entry) => (
        entry.id === card.dataset.itemId && entry.type === card.dataset.itemType
    ));
    if (item) selectCatalogItem(item);
});

elements.seriesSeasonSelect.addEventListener("change", (event) => {
    renderSeriesSeason(event.target.value);
});

elements.episodeList.addEventListener("click", (event) => {
    const episode = event.target.closest("[data-episode-id]");
    if (episode) playSeriesEpisode(episode.dataset.episodeId);
});

elements.detailPlay.addEventListener("click", () => {
    if (!state.activeDetail) return;
    const item = state.activeDetail;
    closeModal("detailModal");
    playItem(item);
});

elements.accountButton.addEventListener("click", handleAccountClick);
elements.footerAccountButton.addEventListener("click", handleAccountClick);
elements.heroPlay.addEventListener("click", () => playItem(state.catalog.find((item) => item.type === "movie") || fallbackCatalog[0]));
elements.discoverButton.addEventListener("click", () => document.querySelector("#catalogue").scrollIntoView({ behavior: "smooth" }));
elements.resetFilters.addEventListener("click", () => {
    resetSearchQuery(false);
    state.activeCategory = "";
    state.activeLanguage = "";
    renderLanguageFilter();
    setFilter("all");
});
elements.authTabs.addEventListener("click", (event) => {
    const button = event.target.closest("[data-auth-mode]");
    if (!button) return;
    if (button.dataset.authMode === "register") {
        window.location.href = "/signup.html";
        return;
    }
    setAuthMode(button.dataset.authMode);
});
elements.authForm.addEventListener("submit", submitAuth);
elements.settingsNav.addEventListener("click", (event) => {
    const button = event.target.closest("[data-settings-tab]");
    if (button) switchSettingsTab(button.dataset.settingsTab);
});
elements.profileSettingsForm.addEventListener("submit", submitProfileSettings);
elements.playbackSettingsForm.addEventListener("submit", submitPlaybackSettings);
elements.passwordSettingsForm.addEventListener("submit", submitPasswordSettings);
elements.settingsTwoFactor.addEventListener("change", toggleTwoFactor);
elements.logoutAllButton.addEventListener("click", logoutAllDevices);
elements.logoutButton.addEventListener("click", logout);

document.addEventListener("click", (event) => {
    if (!elements.searchShell.contains(event.target)) {
        closeSearchSuggestions();
    }
    const closeButton = event.target.closest("[data-close-modal]");
    if (closeButton) closeModal(closeButton.dataset.closeModal);
});

document.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    const openModalElement = [...document.querySelectorAll(".modal")].find((modal) => !modal.hidden);
    if (openModalElement) closeModal(openModalElement.id);
});

elements.streamPlayer.addEventListener("playing", () => {
    if (state.activeSessionToken) {
        setPlayerPlaying();
    }
});

elements.streamPlayer.addEventListener("loadstart", () => {
    if (state.activeSessionToken) {
        setPlayerBuffering("Chargement du programme...", "Ouverture du flux vidéo.");
    }
});

elements.streamPlayer.addEventListener("waiting", () => {
    if (state.activeSessionToken) {
        const text = state.playerHasStarted
            ? "Mise en mémoire tampon..."
            : "Chargement des premières images...";
        setPlayerBuffering(text, "Mise en mémoire tampon...");
        schedulePlayerRecovery("La mise en mémoire tampon dure trop longtemps.");
    }
});

elements.streamPlayer.addEventListener("stalled", () => {
    if (state.activeSessionToken) {
        setPlayerBuffering(
            "Le fournisseur met plus de temps à répondre...",
            "Chargement du flux en cours."
        );
        schedulePlayerRecovery("Le fournisseur ne renvoie plus assez de données.");
    }
});

elements.streamPlayer.addEventListener("canplay", () => {
    if (!state.activeSessionToken) return;
    if (state.playerHasStarted && !elements.streamPlayer.paused) {
        setPlayerPlaying();
    } else if (!state.playerHasStarted) {
        setPlayerLoading("Démarrage de la lecture...", "Le flux est prêt.");
    }
});

elements.streamPlayer.addEventListener("timeupdate", () => {
    if (state.activeSessionToken && !elements.streamPlayer.paused && elements.streamPlayer.currentTime > 0) {
        state.playerLastProgressAt = Date.now();
        setPlayerPlaying();
    }
});

elements.streamPlayer.addEventListener("progress", () => {
    if (state.activeSessionToken && state.playerHasStarted
        && !elements.streamPlayer.paused && elements.streamPlayer.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA) {
        state.playerLastProgressAt = Date.now();
        setPlayerPlaying();
    }
});

elements.streamPlayer.addEventListener("pause", () => {
    if (state.activeSessionToken && state.playerHasStarted && !elements.streamPlayer.ended) {
        elements.playerPlaceholder.hidden = true;
        elements.playerMessage.textContent = "Lecture en pause.";
    }
});

elements.streamPlayer.addEventListener("error", () => {
    if (state.activeSessionToken && !state.playerOpening && !state.playerRecovering) {
        const code = elements.streamPlayer.error?.code;
        if (code === 2 || code === 3) {
            schedulePlayerRecovery("Le flux a été interrompu, tentative de reprise.");
            return;
        }
        showPlayerError("Le flux ne répond pas ou son codec n’est pas pris en charge par ce navigateur.");
    }
});
elements.playerQuality.addEventListener("change", changePlayerQuality);

renderCatalog();
applyLaunchIntent();
restoreSession();
