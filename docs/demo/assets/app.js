(() => {
  "use strict";

  const THEMES = { dark: "dark", light: "light" };

  const TABS = new Set([
    "overview-list",
    "overview-dials",
    "overview-credits",
    "resets",
    "settings-appearance",
    "settings-providers",
    "settings-notifications",
    "settings-status-icons",
  ]);

  const SETTINGS = [
    "settings-appearance",
    "settings-providers",
    "settings-notifications",
    "settings-status-icons",
  ];

  const HASH = {
    "overview-list": "overview",
    "overview-dials": "overview/dials",
    "overview-credits": "overview/credits",
    "account-claude-max": "account/claude-max",
    "account-claude-pro": "account/claude-pro",
    "credit-openrouter": "credit/openrouter",
    resets: "resets",
    "settings-appearance": "settings",
    "settings-providers": "settings/providers",
    "settings-notifications": "settings/notifications",
    "settings-status-icons": "settings/status-icons",
    shade: "shade",
    home: "home",
  };

  const FROM_HASH = Object.fromEntries(
    Object.entries(HASH).map(([id, hash]) => [hash, id])
  );

  // Coordinates are percentages of the 1080×2424 screen capture.
  const L = 3.89, R = 96.2, NAV_TOP = 88.8, NAV_BOT = 97.4;
  const COL_L = [3.89, 48.8], COL_R = [51.2, 96.2];

  const COMMON_NAV = [
    { id: "nav-overview", l: 0, t: NAV_TOP, r: 33.3, b: NAV_BOT, go: "overview", kind: "nav", label: "Overview" },
    { id: "nav-resets", l: 33.3, t: NAV_TOP, r: 66.7, b: NAV_BOT, go: "resets", kind: "nav", label: "Resets" },
    { id: "nav-settings", l: 66.7, t: NAV_TOP, r: 100, b: NAV_BOT, go: "settings", kind: "nav", label: "Settings" },
    { id: "status-bar", l: 0, t: 0, r: 100, b: 3.4, go: "shade", kind: "status", label: "Notification shade" },
  ];

  const BACK = [
    { id: "back-edge", l: 0, t: 0, r: 4.5, b: 100, go: "back", kind: "edge", label: "Back" },
    { id: "back-gesture", l: 28, t: 97.4, r: 72, b: 100, go: "back", kind: "gesture", label: "Back" },
  ];

  const SCREENS = {
    "overview-list": {
      image: "overview-list",
      tab: true,
      swipe: { up: "overview-credits" },
      hotspots: [
        ...COMMON_NAV,
        { id: "card-max", l: L, t: 19.1, r: R, b: 35.27, go: "account-claude-max", kind: "card", hint: true, label: "Claude Max 5x" },
        { id: "card-pro", l: L, t: 48.93, r: R, b: 62.58, go: "account-claude-pro", kind: "card", label: "Claude Pro" },
        { id: "scroll-credits", l: 20, t: 85.2, r: 80, b: 88.7, go: "overview-credits", kind: "card", label: "Show credits" },
      ],
    },
    "overview-dials": {
      image: "overview-dials",
      tab: true,
      swipe: { up: "overview-credits" },
      hotspots: [
        ...COMMON_NAV,
        { id: "dial-max", l: COL_L[0], t: 13.45, r: COL_L[1], b: 35.02, go: "account-claude-max", kind: "dial", label: "Claude Max 5x" },
        { id: "dial-pro", l: COL_L[0], t: 36.1, r: COL_L[1], b: 57.67, go: "account-claude-pro", kind: "dial", label: "Claude Pro" },
        { id: "add-account", l: COL_R[0], t: 79.41, r: COL_R[1], b: 88.7, go: "sheet-account", kind: "dial", label: "Add account" },
      ],
    },
    "overview-credits": {
      image: "overview-credits",
      tab: true,
      swipe: { down: "overview" },
      hotspots: [
        ...COMMON_NAV,
        { id: "add-credit", l: 18.5, t: 14.8, r: 49, b: 17.8, go: "sheet-credit", kind: "chip", label: "Add credit balance" },
        { id: "credit-openrouter", l: L, t: 19.51, r: R, b: 33.25, go: "credit-openrouter", kind: "card", label: "OpenRouter team" },
      ],
    },
    "account-claude-max": {
      image: "account-claude-max",
      tab: false,
      hotspots: [...BACK, { id: "status-bar", l: 0, t: 0, r: 100, b: 3.4, go: "shade", kind: "status", label: "Notification shade" }],
    },
    "account-claude-pro": {
      image: "account-claude-pro",
      tab: false,
      hotspots: [...BACK, { id: "status-bar", l: 0, t: 0, r: 100, b: 3.4, go: "shade", kind: "status", label: "Notification shade" }],
    },
    "credit-openrouter": {
      image: "credit-detail",
      tab: false,
      hotspots: [...BACK, { id: "status-bar", l: 0, t: 0, r: 100, b: 3.4, go: "shade", kind: "status", label: "Notification shade" }],
    },
    resets: {
      image: "resets",
      tab: true,
      hotspots: [
        ...COMMON_NAV,
        { id: "reset-personal", l: L, t: 28.84, r: R, b: 34.86, go: "account-claude-pro", kind: "card", label: "Personal, 5h limit" },
        { id: "reset-work", l: L, t: 35.02, r: R, b: 41.05, go: "account-claude-max", kind: "card", label: "Work, 5h limit" },
        { id: "reset-work-weekly", l: L, t: 74.38, r: R, b: 86.63, go: "account-claude-max", kind: "card", label: "Work, weekly limits" },
      ],
    },
    "settings-appearance": {
      image: "settings-appearance",
      tab: true,
      swipe: { up: "settings-providers" },
      hotspots: [
        ...COMMON_NAV,
        { id: "theme-pure-black", l: 51.5, t: 35.7, r: 92.0, b: 40.7, go: "theme-dark", kind: "chip", label: "Pure black" },
        { id: "theme-light-paper", l: 51.5, t: 41.9, r: 92.0, b: 46.7, go: "theme-light", kind: "chip", label: "Light paper" },
        { id: "open-providers", l: L, t: 84.6, r: R, b: 88.7, go: "settings-providers", kind: "card", label: "Provider icons" },
      ],
    },
    "settings-providers": {
      image: "settings-providers",
      tab: true,
      swipe: { down: "settings-appearance", up: "settings-notifications" },
      hotspots: [...COMMON_NAV],
    },
    "settings-notifications": {
      image: "settings-notifications",
      tab: true,
      swipe: { down: "settings-providers", up: "settings-status-icons" },
      hotspots: [
        ...COMMON_NAV,
        { id: "open-status-icons", l: L, t: 75.3, r: R, b: 88.7, go: "settings-status-icons", kind: "card", label: "Status bar icons" },
      ],
    },
    "settings-status-icons": {
      image: "settings-status-icons",
      tab: true,
      swipe: { down: "settings-notifications" },
      hotspots: [...COMMON_NAV],
    },
    shade: {
      image: "notification-shade",
      tab: false,
      overlay: true,
      swipe: { up: "back" },
      hotspots: [
        { id: "shade-claude", l: 4, t: 24.4, r: 96, b: 32.1, go: "account-claude-max", kind: "card", label: "Claude, exhausted" },
        { id: "shade-dismiss", l: 0, t: 40.5, r: 100, b: 100, go: "back", kind: "card", label: "Close notification shade" },
        { id: "back-edge", l: 0, t: 0, r: 4.5, b: 100, go: "back", kind: "edge", label: "Back" },
        { id: "back-gesture", l: 28, t: 97.4, r: 72, b: 100, go: "back", kind: "gesture", label: "Back" },
      ],
    },
    home: {
      image: "home",
      tab: false,
      sharedImage: true,
      swipe: {},
      hotspots: [
        { id: "status-bar", l: 0, t: 0, r: 100, b: 3.4, go: "shade", kind: "status", label: "Notification shade" },
        { id: "widget-summary", l: 5, t: 13.4, r: 95, b: 25.3, go: "account-claude-pro", kind: "card", label: "Usage summary widget" },
        { id: "widget-rings", l: 5, t: 26.5, r: 95, b: 58, go: "overview-dials", kind: "card", label: "Account rings widget" },
        { id: "widget-mini", l: 5, t: 59.0, r: 48.5, b: 76.8, go: "overview-list", kind: "card", label: "Mini rings widget" },
        { id: "widget-credits", l: 51, t: 59.0, r: 95, b: 76.8, go: "overview-credits", kind: "card", label: "Credits widget" },
        { id: "dock-app", l: 43.2, t: 83.8, r: 56.8, b: 89.9, go: "overview", kind: "round", label: "Open Usage Limits" },
      ],
    },
  };

  const SHEETS = {
    "sheet-account": {
      title: "Add account",
      body: "In the app, this opens the provider list and the provider’s own sign-in.",
    },
    "sheet-credit": {
      title: "Add credit balance",
      body: "In the app, this is where you add a prepaid API key for a credit provider.",
    },
  };


  // Short copy for the live context panel. Only describes behaviour that
  // exists in the app (overview, credits, resets, pinned status icons, widgets).
  const GUIDE = {
    "overview-list": {
      title: "Overview, as a list",
      body: "Each subscription shows its usage windows, how much is left and the next reset. Expired sign-ins come first, then the lowest headline. Filters: All, Needs attention, Resets today.",
      hint: "Tap an account to open its detail. Swipe up, or use the area above the tab bar, to reach prepaid credits.",
    },
    "overview-dials": {
      title: "Overview, as dials",
      body: "The same accounts as rings: each dial shows the account’s headline. Switch between list and dials, and between % left and % used, in Settings.",
      hint: "Tap a dial for that account’s windows. The empty tile opens Add account — the same sign-in flow the app uses.",
    },
    "overview-credits": {
      title: "Prepaid API credits",
      body: "Prepaid API balances sit below your subscription limits. The app reads each one with your own key; there is no server in between.",
      hint: "Tap a row for that key’s limit and reset. Add credit balance is where you paste a provider key.",
    },
    "account-claude-max": {
      title: "Claude Max 5x, in detail",
      body: "Every window of one account: 5-hour, weekly and per-model limits, how much is left and when each resets.",
      hint: "Swipe in from the left edge, or tap the bar at the bottom, to go back.",
    },
    "account-claude-pro": {
      title: "Claude Pro, in detail",
      body: "The same detail layout for Claude Pro: 5-hour and weekly windows with % left and reset times.",
      hint: "Swipe in from the left edge, or tap the bar at the bottom, to go back.",
    },
    "credit-openrouter": {
      title: "A credit account, in detail",
      body: "A prepaid API balance with the remaining amount and, where the provider has one, its reset. The key is stored encrypted on the device (Android Keystore / iOS Keychain).",
      hint: "Swipe in from the left edge, or tap the bar at the bottom, to go back.",
    },
    resets: {
      title: "When limits reset",
      body: "Upcoming 5-hour and weekly resets, grouped by day, with a live countdown to the next one.",
      hint: "Tap a row to open that account. The Overview, Resets and Settings tabs at the bottom move around the app.",
    },
    "settings-appearance": {
      title: "Appearance",
      body: "Four themes (Warm dark, Pure black, Cool graphite, Light paper) plus bundled fonts. Pure black is the default; this preview offers Pure black and Light paper.",
      hint: "Tap Pure black or Light paper to switch the preview theme. Swipe up for provider icon settings.",
    },
    "settings-providers": {
      title: "Provider icons",
      body: "Choose how each service’s icon is drawn in the app: Color, Monochrome or Wordmark, plus the Claude Code figure for Claude.",
      hint: "Swipe down to return to Appearance, or swipe up for notification settings.",
    },
    "settings-notifications": {
      title: "Alerts",
      body: "Alerts for below 20% or 10% left, an exhausted limit, an approaching reset, Codex reset credits and an expired sign-in. Each type can be switched off.",
      hint: "Open Status bar icons at the bottom of this screen, or swipe up.",
    },
    "settings-status-icons": {
      title: "Status bar icons",
      body: "Pin up to three usage accounts: each shows its glyph and headline next to the clock on Android, or on the Lock Screen on iPhone. Off until you turn it on. Credits cannot be pinned.",
      hint: "Swipe down to return to Alerts. Swipe down from the clock on any screen for the notification shade.",
    },
    shade: {
      title: "Notification shade",
      body: "Pinned accounts appear here: glyph, how much is left and the next reset. Codex is at 99% left, Claude is exhausted, Grok is at 76% left.",
      hint: "Tap the Claude row to open that account, or tap the empty area below (or swipe up) to close the shade.",
    },
    home: {
      title: "Home screen and widgets",
      body: "Widgets come as Usage Bars, Usage Summary (Android), Account Rings, Mini Rings and Credits. The usage styles can show all accounts, one service, one account or a custom selection. They only read cached numbers.",
      hint: "Tap a widget to jump into the matching screen, or the app icon in the dock. Swipe down from the clock for the shade.",
    },
  };

  const GUIDE_SHEETS = {
    "sheet-account": {
      title: "Add account",
      body: "Opens the supported services and that provider’s own sign-in: in the browser, with a device code or with an API key.",
      hint: "Close the sheet to return to the overview.",
    },
    "sheet-credit": {
      title: "Add credit balance",
      body: "Add a prepaid API key for a credit provider. The app talks to the provider directly.",
      hint: "Close the sheet to return to the credits list.",
    },
  };

  const state = {
    screen: "overview-list",
    theme: "dark",
    layout: "list", // list | dials
    stack: [],
    behindShade: null,
    sheet: null,
    hinted: false,
  };

  const els = {};

  function preferredTheme() {
    return window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
  }

  function overviewScreen() {
    return state.layout === "dials" ? "overview-dials" : "overview-list";
  }

  function resolve(target) {
    if (target === "overview") return overviewScreen();
    if (target === "settings") return "settings-appearance";
    return target;
  }

  function imagePath(id) {
    const cfg = SCREENS[id];
    if (cfg.sharedImage) return "assets/screens/home.webp";
    return `assets/screens/${state.theme}/${cfg.image}.webp`;
  }

  function setTheme(theme, { fromUser } = {}) {
    if (theme !== "dark" && theme !== "light") return;
    state.theme = theme;
    document.documentElement.setAttribute("data-theme", theme);
    if (els.screen) els.screen.dataset.theme = theme;
    if (els.img) els.img.src = imagePath(visibleScreen());
    updateThemeButtons();
    if (fromUser) history.replaceState(null, "", `#${HASH[visibleScreen()] || ""}`);
  }

  function visibleScreen() {
    return state.behindShade ? "shade" : state.screen;
  }

  function canGoBack() {
    return Boolean(
      state.sheet ||
        state.behindShade ||
        state.stack.length ||
        (SCREENS[state.screen] && !SCREENS[state.screen].tab && state.screen !== "home") ||
        state.screen === "overview-credits" ||
        (SETTINGS.includes(state.screen) && state.screen !== "settings-appearance")
    );
  }

  function goBack() {
    if (state.sheet) {
      state.sheet = null;
      render();
      return;
    }
    if (state.behindShade) {
      state.behindShade = null;
      render();
      return;
    }
    if (state.stack.length) {
      state.screen = state.stack.pop();
      render();
      return;
    }
    if (state.screen === "overview-credits") {
      state.screen = overviewScreen();
      render();
      return;
    }
    if (SETTINGS.includes(state.screen) && state.screen !== "settings-appearance") {
      const i = SETTINGS.indexOf(state.screen);
      state.screen = SETTINGS[Math.max(0, i - 1)];
      render();
      return;
    }
    const cfg = SCREENS[state.screen];
    if (cfg && !cfg.tab && state.screen !== "home") {
      // A detail screen opened without history (deep link, widget, shade) goes back to Overview.
      state.screen = overviewScreen();
      render();
    }
  }

  function visit(target, { push, replace } = {}) {
    if (!target) return;
    if (target === "back") {
      goBack();
      return;
    }
    if (target === "theme-dark" || target === "theme-light") {
      setTheme(target === "theme-dark" ? "dark" : "light", { fromUser: true });
      return;
    }
    if (target.startsWith("sheet-")) {
      state.sheet = target;
      render();
      return;
    }

    const next = resolve(target);

    if (next === "shade") {
      if (!state.behindShade) state.behindShade = state.screen;
      render();
      return;
    }

    const leavingOverlay = Boolean(state.behindShade);
    if (leavingOverlay) state.behindShade = null;

    if (next === state.screen && !leavingOverlay) {
      render();
      return;
    }

    const nextCfg = SCREENS[next];
    const shouldPush =
      push === true ||
      (push !== false && nextCfg && !nextCfg.tab && next !== "home" && !leavingOverlay);

    if (shouldPush) {
      state.stack.push(state.screen);
    } else if (nextCfg && nextCfg.tab) {
      state.stack = [];
      if (next === "overview-list" || next === "overview-dials") {
        state.layout = next === "overview-dials" ? "dials" : "list";
      }
    } else if (next === "home") {
      state.stack = [];
    }

    // Opening a widget from Home should land in the app, not keep a back stack to Home.
    if (state.screen === "home" && next !== "home" && next !== "shade") {
      state.stack = [];
    }

    state.screen = next;
    render();
  }

  function syncHash() {
    const id = visibleScreen();
    const hash = HASH[id] || "";
    const next = `#${hash}`;
    if (location.hash !== next) history.replaceState(null, "", next || "#");
  }

  function screenFromHash() {
    const raw = (location.hash || "").replace(/^#/, "");
    return FROM_HASH[raw] || null;
  }

  function applyHash() {
    const id = screenFromHash();
    if (!id) return;
    state.sheet = null;
    if (id === "shade") {
      if (!state.behindShade) state.behindShade = state.screen || "overview-list";
      render({ skipHash: true });
      return;
    }
    state.behindShade = null;
    state.stack = [];
    if (id === "overview-list" || id === "overview-dials") {
      state.layout = id === "overview-dials" ? "dials" : "list";
    }
    state.screen = id;
    render({ skipHash: true });
  }

  function renderHotspots() {
    const id = visibleScreen();
    const cfg = SCREENS[id];
    const layer = els.hotspots;
    layer.replaceChildren();
    for (const hs of cfg.hotspots) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = `hotspot${hs.kind ? " " + hs.kind : ""}`;
      btn.dataset.hotspot = hs.id;
      btn.dataset.go = hs.go || "";
      btn.setAttribute("aria-label", hs.label);
      btn.title = hs.label;
      btn.style.left = hs.l + "%";
      btn.style.top = hs.t + "%";
      btn.style.width = hs.r - hs.l + "%";
      btn.style.height = hs.b - hs.t + "%";
      if (hs.hint && !state.hinted && id === "overview-list") btn.classList.add("hint");
      btn.addEventListener("click", (ev) => {
        ev.preventDefault();
        ev.stopPropagation();
        if (state.swipedAt && Date.now() - state.swipedAt < 400) return;
        onHotspot(hs);
      });
      layer.appendChild(btn);
    }

  }

  function onHotspot(hs) {
    if (!state.hinted) {
      state.hinted = true;
    }
    if (hs.go) {
      visit(hs.go);
      return;
    }
    // Visible rows without a detail screen still give a brief highlight.
  }

  function renderSheet() {
    const sheet = els.sheet;
    const info = state.sheet ? SHEETS[state.sheet] : null;
    if (!info) {
      sheet.hidden = true;
      sheet.setAttribute("aria-hidden", "true");
      return;
    }
    els.sheetTitle.textContent = info.title;
    els.sheetBody.textContent = info.body;
    sheet.hidden = false;
    sheet.setAttribute("aria-hidden", "false");
  }

  function updateChrome() {
    const id = visibleScreen();
    for (const btn of els.layout.querySelectorAll("button")) {
      const on =
        (btn.dataset.layout === "list" && (id === "overview-list" || (TABS.has(id) && state.layout === "list" && id !== "overview-dials" && id !== "overview-credits"))) ||
        (btn.dataset.layout === "dials" && (id === "overview-dials" || (id !== "overview-list" && state.layout === "dials")));
      // Simpler: pressed state follows layout preference, plus current overview screen.
      btn.setAttribute(
        "aria-pressed",
        String(
          (btn.dataset.layout === "list" && state.layout === "list") ||
            (btn.dataset.layout === "dials" && state.layout === "dials")
        )
      );
    }
    els.homeBtn.setAttribute("aria-pressed", String(id === "home"));
    updateThemeButtons();
  }

  function updateThemeButtons() {
    if (!els.theme) return;
    for (const btn of els.theme.querySelectorAll("button")) {
      btn.setAttribute("aria-pressed", String(btn.dataset.theme === state.theme));
    }
  }


  function guideContent() {
    if (state.sheet && GUIDE_SHEETS[state.sheet]) return GUIDE_SHEETS[state.sheet];
    return GUIDE[visibleScreen()] || {
      title: "Usage Limits",
      body: "Tap through the Android app.",
      hint: "",
    };
  }

  function renderGuide() {
    const info = guideContent();
    const title = document.getElementById("guide-title");
    const body = document.getElementById("guide-body");
    const hint = document.getElementById("guide-hint");
    const box = document.getElementById("guide");
    if (!title || !body || !hint || !box) return;
    const next = info.title + "\n" + info.body + "\n" + (info.hint || "");
    if (box.dataset.sig === next) return;
    const apply = () => {
      title.textContent = info.title;
      body.textContent = info.body;
      hint.textContent = info.hint || "";
      hint.hidden = !info.hint;
      box.dataset.sig = next;
      box.classList.remove("is-updating");
    };
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reduce || !box.dataset.sig) {
      apply();
      return;
    }
    box.classList.add("is-updating");
    window.setTimeout(apply, 160);
  }

  function render({ skipHash } = {}) {
    const id = visibleScreen();
    const src = imagePath(id);
    if (els.img.getAttribute("src") !== src) {
      els.img.setAttribute("src", src);
    }
    els.img.alt = altFor(id);
    els.screen.dataset.screen = id;
    els.screen.dataset.theme = state.theme;
    renderHotspots();
    renderSheet();
    renderGuide();
    updateChrome();
    if (!skipHash) syncHash();
  }

  function altFor(id) {
    const names = {
      "overview-list": "Overview, list of accounts",
      "overview-dials": "Overview, account dials",
      "overview-credits": "Overview, prepaid API credits",
      "account-claude-max": "Claude Max 5x account detail",
      "account-claude-pro": "Claude Pro account detail",
      "credit-openrouter": "OpenRouter credit account detail",
      resets: "Upcoming resets",
      "settings-appearance": "Settings, appearance and theme",
      "settings-providers": "Settings, provider icons",
      "settings-notifications": "Settings, notifications",
      "settings-status-icons": "Settings, status bar icons",
      shade: "Notification shade with pinned status icons",
      home: "Home screen with widgets and status bar icons",
    };
    return names[id] || "Usage Limits";
  }

  function bindSwipes() {
    const el = els.screen;
    let sx = 0, sy = 0, sw = 1, sh = 1, armed = false;

    el.addEventListener(
      "pointerdown",
      (ev) => {
        if (ev.button && ev.button !== 0) return;
        // Let buttons and the sheet keep the pointer so their click handlers fire.
        // The edge, clock and gesture strips are taps and swipe starts at once.
        if (ev.target.closest(".sheet, button") && !ev.target.closest(".hotspot.edge, .hotspot.status, .hotspot.gesture")) {
          armed = false;
          return;
        }
        const rect = el.getBoundingClientRect();
        sx = ev.clientX - rect.left;
        sy = ev.clientY - rect.top;
        sw = rect.width;
        sh = rect.height;
        armed = true;
      },
      { passive: true }
    );

    el.addEventListener("pointerup", (ev) => {
      if (!armed) return;
      armed = false;
      const rect = el.getBoundingClientRect();
      const dx = ev.clientX - rect.left - sx;
      const dy = ev.clientY - rect.top - sy;
      const absX = Math.abs(dx);
      const absY = Math.abs(dy);
      const min = Math.max(36, sw * 0.12);
      const cfg = SCREENS[visibleScreen()];
      if (absX < min && absY < min) return;
      state.swipedAt = Date.now(); // the click that follows a swipe must not fire again

      if (dx > min && absX > absY && sx < sw * 0.12) {
        if (canGoBack()) goBack();
        return;
      }
      if (dy > min && absY > absX && sy < sh * 0.1) {
        visit("shade");
        return;
      }
      if (!cfg || !cfg.swipe) return;
      if (dy < -min && absY > absX && cfg.swipe.up) {
        visit(cfg.swipe.up);
        return;
      }
      if (dy > min && absY > absX && cfg.swipe.down) {
        visit(cfg.swipe.down);
      }
    });

    el.addEventListener("pointercancel", () => {
      armed = false;
    });
  }

  function init() {
    els.img = document.getElementById("screen-image");
    els.screen = document.getElementById("screen");
    els.hotspots = document.getElementById("hotspots");
    els.sheet = document.getElementById("sheet");
    els.sheetTitle = document.getElementById("sheet-title");
    els.sheetBody = document.getElementById("sheet-body");
    els.layout = document.getElementById("layout-toggle");
    els.theme = document.getElementById("theme-toggle");
    els.homeBtn = document.getElementById("home-btn");

    const fromHash = screenFromHash();
    setTheme(preferredTheme());
    if (fromHash) {
      if (fromHash === "shade") {
        state.screen = "overview-list";
        state.behindShade = "overview-list";
      } else {
        state.screen = fromHash;
        if (fromHash === "overview-dials") state.layout = "dials";
        if (fromHash === "overview-list") state.layout = "list";
      }
    }

    els.layout.addEventListener("click", (ev) => {
      const btn = ev.target.closest("button[data-layout]");
      if (!btn) return;
      state.layout = btn.dataset.layout;
      if (state.screen === "overview-list" || state.screen === "overview-dials") {
        visit(overviewScreen(), { push: false });
      } else {
        updateChrome();
      }
    });

    els.theme.addEventListener("click", (ev) => {
      const btn = ev.target.closest("button[data-theme]");
      if (!btn) return;
      setTheme(btn.dataset.theme, { fromUser: true });
    });

    els.homeBtn.addEventListener("click", () => visit("home", { push: false }));

    document.getElementById("sheet-close").addEventListener("click", () => {
      state.sheet = null;
      render();
    });
    els.sheet.addEventListener("click", (ev) => {
      if (ev.target === els.sheet) {
        state.sheet = null;
        render();
      }
    });

    window.addEventListener("hashchange", applyHash);
    window.addEventListener("keydown", (ev) => {
      if (ev.key === "Escape" || ev.key === "Backspace") {
        if (ev.key === "Backspace" && ev.target && ev.target.closest("input,textarea,[contenteditable]")) return;
        if (canGoBack() || state.sheet) {
          ev.preventDefault();
          goBack();
        }
      }
    });

    bindSwipes();
    render();
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
  else init();
})();
