/* Usage Limits — interactive preview.
   Every screen is built from the data below and the real app's strings (app/src/main/res/values/
   strings.xml). The phone screen is a 360×800 dp layer scaled to the frame, so hit areas are the
   elements themselves at any width. Navigation keeps a real back stack like the app's NavHost. */
(() => {
  "use strict";

  // ───────────────────────── Demo data ─────────────────────────
  // The demo clock stands at Tue 24 Nov, 04:20. Windows reset `day` days from today at `time`.
  const NOW_MIN = 4 * 60 + 20;
  const DAY_SHORT = ["Tue", "Wed", "Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed"];
  const DAY_LONG = ["Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday", "Monday", "Tuesday", "Wednesday"];

  const ACCOUNTS = [
    { id: "claude-max", prov: "claude", brand: "Claude", tier: "Max 5x", label: "Work", email: "a•••@example.com", updated: "3m",
      windows: [
        { name: "5h limit", left: 0, day: 0, time: "06:32" },
        { name: "Weekly", left: 59, day: 3, time: "09:30" },
        { name: "Weekly (Opus)", left: 37, day: 3, time: "09:30" },
      ] },
    { id: "codex-plus", prov: "openai", brand: "OpenAI Codex", tier: "Plus", label: "Home", email: "y•••@example.com", updated: "3m",
      windows: [
        { name: "5h limit", left: 99, day: 0, time: "08:21" },
        { name: "Weekly", left: 0, day: 1, time: "06:35" },
      ],
      resetCredits: { available: 1, expires: "28d 18h" } },
    { id: "kimi", prov: "kimi", brand: "Kimi", tier: "Moderato", label: "Side project", email: "d•••@example.com", updated: "5m",
      windows: [
        { name: "5h limit", left: 24, day: 0, time: "05:07" },
        { name: "Weekly", left: 48, day: 2, time: "14:20" },
      ] },
    { id: "claude-pro", prov: "claude", brand: "Claude", tier: "Pro", label: "Personal", email: "a•••@example.net", updated: "4m",
      windows: [
        { name: "5h limit", left: 34, day: 0, time: "05:36" },
        { name: "Weekly", left: 78, day: 4, time: "05:55" },
      ] },
    { id: "devin", prov: "devin", brand: "Devin", tier: "Core", label: "Team", email: "t•••@example.com", updated: "6m",
      windows: [{ name: "Monthly ACUs", left: 62, day: 6, time: "08:00" }] },
    { id: "grok", prov: "grok", brand: "Grok", tier: "SuperGrok", label: "Research", email: "r•••@example.com", updated: "4m",
      windows: [{ name: "Weekly credits", left: 76, day: 4, time: "06:30" }] },
    { id: "antigravity", prov: "gemini", brand: "Antigravity", tier: "Pro", label: "Hobby", email: "h•••@example.com", updated: "5m",
      windows: [
        { name: "5h limit", left: 76, day: 0, time: "07:28" },
        { name: "Weekly", left: 88, day: 5, time: "07:28" },
      ] },
  ];

  const CREDITS = [
    { id: "openrouter", prov: "openrouter", name: "OpenRouter team", source: "OpenRouter", amount: 18.4, total: 50, threshold: 5, updated: "6m" },
    { id: "deepseek", prov: "deepseek", name: "DeepSeek", source: "DeepSeek", amount: 7.32, total: null, threshold: 5, updated: "9m" },
    { id: "xai", prov: "xai", name: "xAI API", source: "xAI (prepaid)", amount: 42.75, total: 100, threshold: 5, updated: "8m" },
    { id: "fal", prov: "fal", name: "fal.ai", source: "fal.ai", amount: 3.1, total: null, threshold: 5, updated: "7m" },
    { id: "novita", prov: "novita", name: "Novita AI", source: "Novita AI", amount: 12.6, total: null, threshold: 5, updated: "10m" },
  ];

  const THEMES = {
    "warm-dark": { label: "Warm dark", ground: "#0F0F0E", ink: "#F0EEE6", page: "dark" },
    "pure-black": { label: "Pure black", ground: "#000000", ink: "#F2F2F2", page: "dark" },
    "cool-graphite": { label: "Cool graphite", ground: "#0E1013", ink: "#ECEFF3", page: "dark" },
    "light-paper": { label: "Light paper", ground: "#F3F0E8", ink: "#1A1918", page: "light" },
  };

  const ICON_CHOICES = {
    codex: { name: "OpenAI Codex", sub: "OpenAI", prov: "openai", options: ["OpenAI", "OpenAI · Wordmark"] },
    claude: { name: "Claude", sub: "Claude Code · Color", prov: "claude", options: ["Claude Code · Color", "Claude Code · Monochrome", "Claude · Color"] },
    antigravity: { name: "Antigravity", sub: "Gemini · Color", prov: "gemini", options: ["Gemini · Color", "Gemini · Monochrome", "Antigravity · Color"] },
    grok: { name: "Grok", sub: "Grok", prov: "grok", options: ["Grok", "Grok · Wordmark"] },
    kimi: { name: "Kimi", sub: "Kimi · Color", prov: "kimi", options: ["Kimi · Color", "Kimi · Monochrome", "Kimi · Wordmark"] },
    devin: { name: "Devin", sub: "Devin · Color", prov: "devin", options: ["Devin · Color", "Devin · Monochrome"] },
  };

  const APP_ICONS = [
    ["default", "Default"], ["gauge-coin", "Gauge Coin"], ["gauge", "Gauge"],
    ["hourglass", "Hourglass"], ["refresh-ring", "Refresh Ring"], ["battery", "Battery"],
  ];

  const ADD_PROVIDERS = [
    { name: "Claude", logo: "claude", hint: "Sign in in the browser" },
    { name: "OpenAI Codex", logo: "openai", hint: "Sign in with a device code" },
    { name: "Antigravity", logo: "gemini", hint: "Sign in with Google" },
    { name: "Grok", logo: "grok", hint: "Sign in in the browser" },
    { name: "Kimi Code", logo: "kimi", hint: "Paste an API key" },
    { name: "Devin", logo: "devin", hint: "Paste an API key" },
    { name: "Z.ai", badge: "Z", hint: "Paste an API key" },
    { name: "MiniMax", badge: "MM", hint: "Paste an API key" },
    { name: "OpenCode Go", badge: "OC", hint: "Paste an API key" },
    { name: "Ollama", badge: "OL", hint: "Paste an API key" },
  ];

  const ADD_CREDITS = [
    { name: "OpenRouter", logo: "openrouter", hint: "Management key for the account balance, or any API key for that key's own limit." },
    { name: "DeepSeek", logo: "deepseek", hint: "DeepSeek API key." },
    { name: "Moonshot", badge: "MS", hint: "Moonshot platform API key (not a Kimi Code key)." },
    { name: "xAI", logo: "xai", hint: "xAI management key (console.x.ai, Settings, Management Keys), not an inference key." },
    { name: "fal", logo: "fal", hint: "fal key with the billing:usage:read scope (the BILLING preset), not an inference key." },
    { name: "Novita AI", logo: "novita", hint: "Novita API key." },
    { name: "Vercel AI Gateway", badge: "▲", hint: "AI Gateway API key (vck_…) or a Vercel access token." },
    { name: "DeepInfra", badge: "DI", hint: "DeepInfra API key." },
    { name: "TokenRouter", badge: "TR", hint: "Management key, not an inference key." },
    { name: "Ollama", badge: "OL", hint: "Ollama API key (ollama.com/settings/keys)." },
  ];

  const SETTINGS_SECTIONS = [
    ["appearance", "Appearance"], ["provider-icons", "Provider icons"], ["app-icon", "App icon"],
    ["sync", "Sync"], ["display", "Display"], ["overview", "Overview"], ["notifications", "Notifications"],
    ["status-icons", "Status bar icons"], ["thresholds", "Thresholds"], ["diagnostics", "Diagnostics"],
  ];

  // ───────────────────────── State ─────────────────────────
  const S = {
    theme: "pure-black",
    layout: "list",
    pct: "left",
    filter: "all",
    surface: "app", // app | home
    overlay: null, // shade | recents
    appClosed: false,
    stack: [{ key: "overview" }],
    sheet: null,
    dialog: null,
    opacity: 70,
    codexReset: false,
    creditsExpanded: false,
    acctNotif: {},
    set: {
      textFont: "Roboto",
      figureFont: "Roboto Mono",
      icons: Object.fromEntries(Object.entries(ICON_CHOICES).map(([k, v]) => [k, v.options[0]])),
      animateClaude: true,
      appIcon: "default",
      interval: 30,
      showTier: true,
      creditLayout: "normal",
      showFirst: "usage",
      showCredits: true,
      notif: { below20: true, below10: true, exhausted: true, resetCredit: true, approaching: true, creditExpiring: true, authExpired: true },
      statusIcons: true,
      pinned: ["codex-plus", "claude-max", "grok"],
    },
  };
  const els = {};
  let rendered = null; // the route object currently on screen (for scroll memory)
  let renderedKey = "";

  // ───────────────────────── Helpers ─────────────────────────
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const reduced = () => window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const acct = (id) => ACCOUNTS.find((a) => a.id === id);
  const credit = (id) => CREDITS.find((c) => c.id === id);
  const top = () => S.stack[S.stack.length - 1];

  function minsUntil(w) {
    const [h, m] = w.time.split(":").map(Number);
    return w.day * 1440 + h * 60 + m - NOW_MIN;
  }
  function countdown(min) {
    if (min < 60) return `${min}m`;
    if (min < 1440) { const h = Math.floor(min / 60), m = min % 60; return m ? `${h}h ${m}m` : `${h}h`; }
    const d = Math.floor(min / 1440), h = Math.floor((min % 1440) / 60);
    return h ? `${d}d ${h}h` : `${d}d`;
  }
  const atLabel = (w) => (w.day === 0 ? w.time : `${DAY_SHORT[w.day]} ${w.time}`);
  const time12 = (t) => { const [h, m] = t.split(":").map(Number); return `${h % 12 || 12}:${String(m).padStart(2, "0")} ${h < 12 ? "AM" : "PM"}`; };
  const dateOf = (day) => `${24 + day} Nov`;
  const widgetWhen = (w) => (w.day === 0 ? time12(w.time) : DAY_SHORT[w.day]);

  function windows(a) {
    if (a.id === "codex-plus" && S.codexReset) {
      return [a.windows[0], { name: "Weekly", left: 100, day: 7, time: "04:20" }];
    }
    return a.windows;
  }
  function headline(a) {
    return windows(a).reduce((best, w) => (w.left < best.left ? w : best));
  }
  function soonest(a) {
    return windows(a).reduce((best, w) => (minsUntil(w) < minsUntil(best) ? w : best));
  }
  function sev(left) {
    if (left <= 0) return "exhausted";
    if (left <= 20) return "low";
    if (left <= 50) return "medium";
    return "healthy";
  }
  const SEV_LABEL = { exhausted: "Exhausted", low: "Low", medium: "Moderate", healthy: "Healthy" };
  const WIDGET_STATUS = { exhausted: "Out", low: "Low", medium: "Fair", healthy: "OK" };
  const shown = (left) => (S.pct === "left" ? left : 100 - left);
  const side = () => (S.pct === "left" ? "left" : "used");
  const figClass = (left) => (left <= 0 ? "fig-exhausted" : left <= 20 ? "fig-low" : "");
  const fullName = (a) => `${a.brand} ${a.tier}`;
  const listName = (a) => (S.set.showTier ? fullName(a) : a.brand);
  const creditsRemaining = (a) => (a.resetCredits && !S.codexReset ? a.resetCredits.available : 0);
  const money = (n) => `US$${n.toFixed(2)}`;
  function creditSev(c) {
    if (c.amount < c.threshold) return "low";
    if (c.total) return sev(Math.round((c.amount / c.total) * 100));
    return null;
  }
  function orderedAccounts() {
    return [...ACCOUNTS].sort((x, y) => headline(x).left - headline(y).left || ACCOUNTS.indexOf(x) - ACCOUNTS.indexOf(y));
  }
  function byNextReset() {
    return [...ACCOUNTS].sort((x, y) => minsUntil(soonest(x)) - minsUntil(soonest(y)));
  }
  function nextReset() {
    let best = null;
    for (const a of ACCOUNTS) for (const w of windows(a)) if (!best || minsUntil(w) < minsUntil(best)) best = w;
    return best;
  }

  // ───────────────────────── Icons ─────────────────────────
  const I = {
    chevron: '<svg class="chev" viewBox="0 0 24 24" aria-hidden="true"><path d="m9.5 6 6 6-6 6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    refresh: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M17.65 6.35A7.96 7.96 0 0 0 12 4a8 8 0 1 0 7.73 10h-2.08A6 6 0 1 1 12 6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35Z"/></svg>',
    plus: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>',
    home: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M4 10.4 12 4l8 6.4V20a1 1 0 0 1-1 1h-4.8v-6H9.8v6H5a1 1 0 0 1-1-1Z"/></svg>',
    clock: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="8.6" fill="none" stroke="currentColor" stroke-width="1.9"/><path d="M12 7.2V12l3.2 2" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"/></svg>',
    gear: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M19.14 12.94a7.07 7.07 0 0 0 0-1.88l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96a7.03 7.03 0 0 0-1.62-.94l-.36-2.54a.48.48 0 0 0-.48-.41h-3.84a.48.48 0 0 0-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.49.49 0 0 0-.59.22L2.74 8.87a.48.48 0 0 0 .12.61l2.03 1.58a7.4 7.4 0 0 0 0 1.88l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32a.49.49 0 0 0-.12-.61ZM12 15.6a3.6 3.6 0 1 1 0-7.2 3.6 3.6 0 0 1 0 7.2Z"/></svg>',
    back: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M16.5 4.8 7 12l9.5 7.2Z" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linejoin="round"/></svg>',
    circle: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="7.4" fill="none" stroke="currentColor" stroke-width="1.9"/></svg>',
    square: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="5.6" y="5.6" width="12.8" height="12.8" rx="2" fill="none" stroke="currentColor" stroke-width="1.9"/></svg>',
    key: '<svg viewBox="0 0 26 14" aria-hidden="true"><path fill="currentColor" d="M7 1a6 6 0 1 0 5.66 8H17v3h3V9h2v3h3V5H12.66A6 6 0 0 0 7 1Zm0 3.4a2.6 2.6 0 1 1 0 5.2 2.6 2.6 0 0 1 0-5.2Z"/></svg>',
    bt: '<svg viewBox="0 0 14 24" aria-hidden="true"><path d="m2 7 10 10-5 5V2l5 5L2 17" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    mute: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M12 22a2.2 2.2 0 0 0 2.2-2.2H9.8A2.2 2.2 0 0 0 12 22Zm6.6-6V11c0-3.3-1.8-6-4.9-6.7V3.6a1.7 1.7 0 0 0-3.4 0v.7c-.6.13-1.16.36-1.67.67L18.6 15.9V16ZM3.3 2 2 3.3l4.6 4.6A7.6 7.6 0 0 0 5.4 11v5L3.2 18.2v1h14.9l2.6 2.6 1.3-1.3Z"/></svg>',
    signal: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M22 2v20H2Z"/></svg>',
    phone: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round" d="M6.6 3.5H4.5a1 1 0 0 0-1 1.1C4 13 11 20 19.4 20.5a1 1 0 0 0 1.1-1v-2.1a1 1 0 0 0-.8-1l-3.1-.7a1 1 0 0 0-1 .3l-1.4 1.6a13.4 13.4 0 0 1-5.8-5.8L10 10.4a1 1 0 0 0 .3-1l-.7-3.1a1 1 0 0 0-1-.8Z"/></svg>',
    msg: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round" d="M12 3.6a8.4 8.4 0 0 0-7.3 12.6L3.6 20.4l4.3-1.1A8.4 8.4 0 1 0 12 3.6Z"/></svg>',
    globe: '<svg viewBox="0 0 24 24" aria-hidden="true"><g fill="none" stroke="currentColor" stroke-width="1.6"><circle cx="12" cy="12" r="8.6"/><path d="M3.4 12h17.2M12 3.4c2.4 2.4 3.6 5.3 3.6 8.6s-1.2 6.2-3.6 8.6c-2.4-2.4-3.6-5.3-3.6-8.6S9.6 5.8 12 3.4Z"/></g></svg>',
    camera: '<svg viewBox="0 0 24 24" aria-hidden="true"><g fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"><path d="M4.5 7.5h3l1.6-2.2h5.8l1.6 2.2h3a1 1 0 0 1 1 1V18a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1V8.5a1 1 0 0 1 1-1Z"/><circle cx="12" cy="13" r="3.4"/></g></svg>',
    mic: '<svg viewBox="0 0 24 24" aria-hidden="true"><g fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><rect x="9" y="3.5" width="6" height="11" rx="3"/><path d="M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v2.5"/></g></svg>',
    lens: '<svg viewBox="0 0 24 24" aria-hidden="true"><g fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M4 8.5V6a2 2 0 0 1 2-2h2.5M15.5 4H18a2 2 0 0 1 2 2v2.5M20 15.5V18a2 2 0 0 1-2 2h-2.5M8.5 20H6a2 2 0 0 1-2-2v-2.5"/><circle cx="12" cy="12" r="3"/></g></svg>',
    wifi: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M12 20.5 1.5 8.3a16 16 0 0 1 21 0Z"/></svg>',
    dnd: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="8.6" fill="none" stroke="currentColor" stroke-width="1.9"/><path d="M7.5 12h9" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/></svg>',
    torch: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" d="M7 3h10v3l-2.5 4v10.5h-5V10L7 6Z"/><path d="M12 13v2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>',
    exhaustedGlyph: '<svg viewBox="0 0 12 12" aria-hidden="true"><circle cx="6" cy="6" r="4.4" fill="none" stroke="currentColor" stroke-width="1.5"/><path d="m3 9 6-6" stroke="currentColor" stroke-width="1.5"/></svg>',
  };

  let ccSeq = 0;
  function claudeLogo(mood, cls = "", still = false) {
    const choice = S.set.icons.claude;
    if (choice === "Claude · Color") return `<svg class="logo ${cls}" aria-hidden="true"><use href="#p-claude"/></svg>`;
    if (!window.ClaudeMotion) return `<svg class="logo ${cls}" style="color:#D97757" aria-hidden="true"><use href="#p-claudecode"/></svg>`;
    let svg = window.ClaudeMotion.svgFor(mood, `cc${++ccSeq}`);
    if (choice === "Claude Code · Monochrome") svg = svg.split(window.ClaudeMotion.FILL).join("currentColor");
    const anim = !still && S.set.animateClaude && !reduced();
    return `<span class="logo cc ${cls}" data-mood="${mood}"${anim ? ' data-anim="1"' : ""} aria-hidden="true">${svg}</span>`;
  }
  function moodOf(a) {
    const h = headline(a);
    return window.ClaudeMotion ? window.ClaudeMotion.moodFromUsage(h.left, h.left <= 0) : "idle";
  }
  function logo(prov, cls = "", opts = {}) {
    if (prov === "claude") return claudeLogo(opts.mood || "idle", cls, opts.still);
    return `<svg class="logo ${cls}" aria-hidden="true"><use href="#p-${prov}"/></svg>`;
  }
  const acctLogo = (a, cls = "", opts = {}) => logo(a.prov, cls, { mood: opts.mood || moodOf(a), still: opts.still });
  function badge(text) { return `<span class="mono-badge" aria-hidden="true">${esc(text)}</span>`; }

  function ring(pct, { stroke = 10, track = "var(--rule)", color = "var(--data)" } = {}) {
    const r = 50 - stroke / 2;
    const c = 2 * Math.PI * r;
    const v = Math.max(0, Math.min(100, pct));
    const arc = v > 0
      ? `<circle cx="50" cy="50" r="${r}" fill="none" stroke="${color}" stroke-width="${stroke}" stroke-linecap="round" stroke-dasharray="${((c * v) / 100).toFixed(2)} ${c.toFixed(2)}" transform="rotate(-90 50 50)"/>`
      : "";
    return `<svg class="ring" viewBox="0 0 100 100" aria-hidden="true"><circle cx="50" cy="50" r="${r}" fill="none" stroke="${track}" stroke-width="${stroke}"/>${arc}</svg>`;
  }
  const bar = (pct, dim = false) => `<span class="bar${dim ? " dim" : ""}" aria-hidden="true"><i style="width:${Math.max(0, Math.min(100, pct))}%"></i></span>`;
  function toggleRow(title, sub, act, arg, checked, { off = false } = {}) {
    return `<button class="trow${off ? " off" : ""}" role="switch" aria-checked="${checked}" data-act="${act}" data-arg="${esc(arg)}">
      <span class="trow-text"><span class="trow-title">${title}</span>${sub ? `<span class="trow-sub">${sub}</span>` : ""}</span>
      <span class="switch" aria-checked="${checked}"></span></button>`;
  }
  function segRow(act, current, options) {
    return `<div class="segrow" role="group">${options.map(([v, l]) =>
      `<button data-act="${act}" data-arg="${esc(v)}" aria-pressed="${String(v) === String(current)}">${l}</button>`).join("")}</div>`;
  }

  // ───────────────────────── Chrome ─────────────────────────
  function statusBarHTML() {
    const pins = S.set.statusIcons ? S.set.pinned.map((id) => {
      const a = acct(id);
      const w = soonest(a);
      const glyph = a.prov === "claude" ? '<svg aria-hidden="true" viewBox="0 0 24 24"><use href="#p-claudecode"/></svg>' : `<svg aria-hidden="true" viewBox="0 0 24 24"><use href="#p-${a.prov}"/></svg>`;
      const fig = w.left <= 0 ? I.exhaustedGlyph : shown(w.left);
      return `<span class="sb-pin">${glyph}${fig}</span>`;
    }).join("") : "";
    return `<span class="sb-left"><span class="sb-time">4:20</span>${pins}</span>
      <span class="sb-right">${I.key}${I.bt}${I.mute}<span class="sb-5g">5G</span>${I.signal}<span class="sb-batt"><b><span>69</span></b><i></i></span></span>`;
  }

  function tabsHTML(active) {
    const tab = (key, label, icon) => `<button data-act="tab" data-arg="${key}"${active === key ? ' aria-current="page"' : ""}><span class="pill">${icon}</span>${label}</button>`;
    return `<nav class="tabs" aria-label="App navigation">${tab("overview", "Overview", I.home)}${tab("resets", "Resets", I.clock)}${tab("settings", "Settings", I.gear)}</nav>`;
  }

  // ───────────────────────── Screens ─────────────────────────
  function overviewHTML() {
    const nr = nextReset();
    const pctChip = `<button class="chip-btn" data-act="pct" aria-label="${S.pct === "left" ? "Show percentage used" : "Show percentage left"}">% ${side()}</button>`;
    const head = `<div class="head"><div class="head-text"><h1 class="title">Usage Limits</h1>
      <p class="subtitle">Next reset <span class="mono">${nr.time}</span></p></div>
      ${pctChip}<button class="icon-btn" data-act="refresh" aria-label="Refresh usage">${I.refresh}</button></div>`;

    let usage;
    if (S.layout === "dials") {
      usage = `<div class="dials">${orderedAccounts().map(dialHTML).join("")}
        <button class="dial add" data-act="push" data-arg="add-account">${I.plus}<span class="dial-sub">Add account</span></button></div>`;
    } else {
      const f = (id, label) => `<button class="filter" data-act="filter" data-arg="${id}" aria-pressed="${S.filter === id}">${label}</button>`;
      const list = orderedAccounts().filter((a) => {
        if (S.filter === "attention") return ["exhausted", "low"].includes(sev(headline(a).left));
        if (S.filter === "today") return windows(a).some((w) => w.day === 0);
        return true;
      });
      usage = `<div class="filters" role="group" aria-label="Filter">${f("all", "All")}${f("attention", "Needs attention")}${f("today", "Resets today")}</div>
        ${list.length ? `<div class="card flush">${list.map(listItemHTML).join("")}</div>` : '<div class="card muted">No accounts match this filter.</div>'}
        <button class="btn" style="margin-top:10px" data-act="push" data-arg="add-account">Add account</button>`;
    }
    const credits = S.set.showCredits ? creditsBlockHTML() : "";
    const body = S.set.showFirst === "credits" ? credits + `<div style="height:6px"></div>` + usage : usage + credits;
    return `<section class="scr" data-screen="overview"><div class="scroll">${head}${body}</div>${tabsHTML("overview")}</section>`;
  }

  function listItemHTML(a) {
    const h = headline(a);
    const s = sev(h.left);
    const rows = windows(a).map((w) => {
      const hl = w === h;
      return `<span class="win-l${hl ? " hl" : ""}">${w.name}</span>${bar(shown(w.left), !hl)}
        <span class="win-p${hl ? "" : " dim"} ${figClass(w.left)}">${shown(w.left)}%</span><span class="win-t">${atLabel(w)}</span>`;
    }).join("");
    return `<button class="acct" data-act="open-account" data-arg="${a.id}" aria-label="${esc(fullName(a))}, ${SEV_LABEL[s]}, ${h.name} ${shown(h.left)}% ${side()}">
      <span class="acct-top">${acctLogo(a)}<span class="acct-name">${esc(listName(a))}</span><span class="dot ${s}" title="${SEV_LABEL[s]}"></span>${I.chevron}</span>
      <span class="acct-email">${a.email}</span>
      <span class="wins">${rows}</span></button>`;
  }

  function dialHTML(a) {
    const h = headline(a);
    return `<button class="dial" data-act="open-account" data-arg="${a.id}" aria-label="${esc(fullName(a))}, ${shown(h.left)}% ${side()}">
      <span class="ring-box">${ring(shown(h.left))}<span class="ring-in">${acctLogo(a)}<b class="${figClass(h.left)}">${shown(h.left)}%</b></span></span>
      <span class="dial-name">${esc(a.brand)}</span><span class="dial-sub">${a.email}</span>
      <span class="dial-line">${h.name} · <span class="mono">${atLabel(h)}</span></span></button>`;
  }

  function creditCardHTML(c) {
    const s = creditSev(c);
    const pct = c.total ? Math.round((c.amount / c.total) * 100) : null;
    return `<button class="credit" data-act="open-credit" data-arg="${c.id}">
      <span class="credit-top">${logo(c.prov)}<span style="flex:1;min-width:0"><span class="credit-name">${esc(c.name)}</span>
        <span class="credit-sub">${esc(c.source)} · Updated ${c.updated} ago</span></span>
        ${s ? `<span class="mark ${s}"><span class="dot ${s === "medium" ? "low" : s}"></span>${SEV_LABEL[s]}</span>` : ""}${I.chevron}</span>
      <span class="credit-amt">${money(c.amount)}${c.total ? `<small>of ${money(c.total)}</small>` : ""}</span>
      ${pct !== null ? bar(pct) : ""}</button>`;
  }

  function creditsBlockHTML() {
    const head = `<div class="section" data-sec="credits">Credits<button class="link" data-act="push" data-arg="add-credit">Add credit balance</button></div>`;
    if (S.set.creditLayout === "compact" && !S.creditsExpanded) {
      const lowest = [...CREDITS].sort((x, y) => x.amount / x.threshold - y.amount / y.threshold)[0];
      return `${head}<button class="credit" data-act="expand-credits" aria-label="Expand credit balances">
        <span class="credit-compact"><span style="flex:1"><span class="credit-name">Credits · ${CREDITS.length} balances</span>
        <span class="credit-sub">Low balance: ${esc(lowest.name)} ${money(lowest.amount)}</span></span>${I.chevron}</span></button>`;
    }
    const collapse = S.set.creditLayout === "compact" ? `<button class="btn slim" style="margin-top:8px" data-act="expand-credits">Collapse</button>` : "";
    return head + CREDITS.map(creditCardHTML).join("") + collapse;
  }

  function accountHTML(id) {
    const a = acct(id);
    const h = headline(a);
    const s = sev(h.left);
    const lims = windows(a).map((w) => `<div class="lim"><div class="lim-top"><span class="lim-name">${w.name}</span>
      <span class="lim-fig ${figClass(w.left)}">${shown(w.left)}% ${side()}</span></div>${bar(shown(w.left))}
      <div class="lim-reset">Reset in ${countdown(minsUntil(w))}</div></div>`).join("");
    let rc = "";
    if (a.resetCredits) {
      const n = creditsRemaining(a);
      rc = `<div class="section">Reset credits</div><div class="card">${n
        ? `<div class="rc-big">${n} available</div><div class="muted">Expires in ${a.resetCredits.expires}</div>
           <button class="btn slim" style="margin-top:10px" data-act="reset-credit">Reset limit</button>`
        : `<div class="rc-big">None available</div><div class="muted">Credits appear here when your plan grants one</div>`}</div>`;
    }
    const notif = S.acctNotif[id] !== false;
    return `<section class="scr" data-screen="account"><div class="scroll">
      <div class="d-head">${acctLogo(a, "lg")}<div style="min-width:0"><div class="d-name">${esc(fullName(a))}</div>
        <div class="d-meta">${a.email}</div><div class="d-upd" data-updated>Updated ${a.updated} ago</div></div>
        <span class="mark ${s}"><span class="dot ${s === "medium" ? "low" : s}"></span>${SEV_LABEL[s]}</span></div>
      <div class="section">Limits</div><div class="card flush">${lims}</div>
      ${rc}
      <div class="section">Account</div><div class="card" style="padding-top:2px;padding-bottom:2px">
        ${toggleRow("Notifications", "Alerts about this account's limits, resets and credits. Its numbers keep updating either way.", "acct-notif", id, notif)}</div>
      <div class="stack"><button class="btn" data-act="acct-refresh">Refresh now</button>
        <p class="fine">Its stored credentials are deleted from this device. Nothing changes on the provider side, and you can add the account again at any time.</p>
        <button class="btn danger" data-act="acct-remove" data-arg="${id}">Remove account</button></div>
    </div></section>`;
  }

  function creditHTML(id) {
    const c = credit(id);
    const s = creditSev(c);
    const pct = c.total ? Math.round((c.amount / c.total) * 100) : null;
    return `<section class="scr" data-screen="credit"><div class="scroll" style="padding-top:44px">
      <div class="card"><div class="credit-top">${logo(c.prov, "lg")}<div style="flex:1;min-width:0"><div class="credit-name">${esc(c.name)}</div>
        <div class="credit-sub">${esc(c.source)} · Updated ${c.updated} ago</div></div>
        ${s ? `<span class="mark ${s}"><span class="dot ${s === "medium" ? "low" : s}"></span>${SEV_LABEL[s]}</span>` : ""}</div>
        <div class="credit-amt">${money(c.amount)}${c.total ? `<small>of ${money(c.total)}</small>` : ""}</div>${pct !== null ? `<div style="margin-top:8px">${bar(pct)}</div>` : ""}</div>
      <div class="card"><div class="trow-title">Edit</div>
        <label class="field"><span>Name</span><input id="cd-name" type="text" value="${esc(c.name)}" autocomplete="off" spellcheck="false"></label>
        <label class="field"><span>Low-balance figure</span><input id="cd-threshold" type="text" inputmode="decimal" value="${c.threshold}" autocomplete="off">
          <div class="fine" id="cd-help">Blank uses the default: 5, or 35 for CNY.</div></label>
        <div class="save-row"><button class="btn ink" data-act="credit-save" data-arg="${id}">Save</button></div></div>
      <div class="stack"><button class="btn" data-act="acct-refresh">Refresh now</button>
        <p class="fine">Its key is deleted from this device. It is not revoked at the provider: do that in the provider's console if the key may have leaked.</p>
        <button class="btn danger" data-act="credit-remove" data-arg="${id}">Remove credit account</button></div>
    </div></section>`;
  }

  function resetsHTML() {
    const rows = [];
    for (const a of ACCOUNTS) for (const w of windows(a)) rows.push({ a, w, m: minsUntil(w) });
    rows.sort((x, y) => x.m - y.m || ACCOUNTS.indexOf(x.a) - ACCOUNTS.indexOf(y.a));
    const groups = [];
    for (const r of rows) {
      const label = r.w.day === 0 ? "Today" : r.w.day === 1 ? "Tomorrow" : `${DAY_LONG[r.w.day]} ${dateOf(r.w.day)}`;
      let g = groups[groups.length - 1];
      if (!g || g.label !== label) groups.push((g = { label, rows: [] }));
      g.rows.push(r);
    }
    let first = true;
    const body = groups.map((g) => `<div class="rgroup">${g.label}</div><div class="card flush">${g.rows.map((r) => {
      const html = `<button class="rrow${first ? " next" : ""}" data-act="open-account" data-arg="${r.a.id}" aria-label="${r.a.label}, ${r.w.name}, resets in ${countdown(r.m)}">
        <span class="t">${r.w.time}</span><span class="dot ${sev(r.w.left)}"></span>${acctLogo(r.a, "", { mood: "hooray" })}
        <span style="min-width:0"><span class="lbl" style="display:block">${r.w.name}</span><span class="who" style="display:block">${esc(r.a.label)}</span></span>
        <span class="in">${countdown(r.m)}</span></button>`;
      first = false;
      return html;
    }).join("")}</div>`).join("");
    return `<section class="scr" data-screen="resets"><div class="scroll">
      <div class="head"><div class="head-text"><h1 class="title">Resets</h1><p class="subtitle">${rows.length} upcoming</p></div></div>
      ${body}</div>${tabsHTML("resets")}</section>`;
  }

  function settingsHTML() {
    const st = S.set;
    const sec = (id, label) => `<div class="section" data-sec="${id}">${label}</div>`;
    const themeOpt = (key) => {
      const t = THEMES[key];
      return `<button class="opt left" data-act="theme" data-arg="${key}" aria-pressed="${S.theme === key}">
        <span class="swatch" style="background:${t.ground};--sw-ink:${t.ink}"></span>${t.label}</button>`;
    };
    const font = (act, cur, v, extra = "") => `<button class="opt${extra ? " stackd" : ""}" data-act="${act}" data-arg="${v}" aria-pressed="${cur === v}">${extra || v}</button>`;
    const used = S.pct === "used";
    const iconRow = (key) => {
      const c = ICON_CHOICES[key];
      const lg = key === "claude" ? claudeLogo("idle") : logo(c.prov);
      const row = `<button class="row-btn" data-act="choose-icon" data-arg="${key}">${lg}
        <span class="trow-text"><span class="trow-title">${c.name}</span><span class="trow-sub">${esc(st.icons[key])}</span></span><span class="row-act">Choose</span></button>`;
      if (key !== "claude") return row;
      return row + `<div class="nested">${toggleRow("Animate Claude Code icon", "Moves with Claude's usage in Overview and Account detail. Off when Android's “Remove animations” is on.", "toggle", "animateClaude", st.animateClaude)}</div>`;
    };
    const pinnedCount = st.pinned.length;
    const pinRows = orderedAccounts().map((a) => toggleRow(esc(a.label), esc(fullName(a)), "pin", a.id, st.pinned.includes(a.id), { off: !st.statusIcons || (!st.pinned.includes(a.id) && pinnedCount >= 3) })).join("");
    const n = st.notif;
    const first = used ? "80%" : "20%";
    const second = used ? "90%" : "10%";
    const word = used ? "Above" : "Below";
    const wordEnd = used ? "used" : "left";

    return `<section class="scr" data-screen="settings"><div class="scroll">
      <div class="head"><div class="head-text"><h1 class="title">Settings</h1></div></div>

      ${sec("appearance", "Appearance")}
      <div class="card">
        <div class="preview-box"><div class="preview-k">PREVIEW</div>
          <div style="display:flex;justify-content:space-between;align-items:baseline;margin:4px 0 6px"><span style="font-weight:500">Claude Pro</span>
          <span><span class="mono fig-low" style="font-size:17px;font-weight:600">${shown(18)}%</span> <span class="muted small">${side()}</span></span></div>
          ${bar(shown(18))}<div class="mono muted small" style="margin-top:6px">23:31 · 1h 18m · US$12.40</div></div>
        <div class="set-title">Theme</div><div class="set-sub">Colours of the app. Widgets keep their own panel style.</div>
        <div class="opts">${themeOpt("warm-dark")}${themeOpt("pure-black")}${themeOpt("cool-graphite")}${themeOpt("light-paper")}</div>
        <div class="set-title">Text font</div><div class="set-sub">Used for all text in the app.</div>
        <div class="opts">${["Roboto", "Roboto Flex", "IBM Plex Sans", "Figtree"].map((f) => font("textfont", st.textFont, f)).join("")}</div>
        <div class="set-title">Figure font</div><div class="set-sub">Used for percentages, times and amounts.</div>
        <div class="opts three">${["Roboto Mono", "JetBrains Mono", "IBM Plex Mono"].map((f) => font("figfont", st.figureFont, f, `<span class="fig">61%</span><small>${f}</small>`)).join("")}</div>
        <button class="row-btn rule-top" style="margin-top:12px" data-act="push" data-arg="licences"><span class="trow-text"><span class="trow-title">Font licences</span></span>${I.chevron}</button>
      </div>

      ${sec("provider-icons", "Provider icons")}
      <div class="card" style="padding-top:2px;padding-bottom:2px">${Object.keys(ICON_CHOICES).map(iconRow).join("")}</div>

      ${sec("app-icon", "App icon")}
      <div class="card"><div class="appicons">${APP_ICONS.map(([k, l]) => `<button class="appicon-opt" data-act="appicon" data-arg="${k}" aria-pressed="${st.appIcon === k}">
        <img src="assets/icons/app-${k}.webp" alt="" width="42" height="42">${l}</button>`).join("")}</div></div>

      ${sec("sync", "Sync")}
      <div class="card"><div class="set-title">Background refresh interval</div><div style="height:8px"></div>
        ${segRow("interval", st.interval, [[5, "5m"], [15, "15m"], [30, "30m"], [60, "1h"], [180, "3h"]])}
        <div class="set-sub" style="margin:8px 0 0">Android may refresh less often in Doze or battery saver.</div></div>

      ${sec("display", "Display")}
      <div class="card"><div class="set-title">Show percentages as</div>
        <div class="set-sub">What is left, or what you have used. Applies to the whole app and to every widget.</div>
        ${segRow("pctmode", S.pct, [["left", "Left"], ["used", "Used"]])}
        <div class="preview-box" style="margin-top:10px"><div class="preview-k">PREVIEW</div><div class="mono" style="margin-top:3px">${shown(61)}% ${side()} · 2h 14m</div></div></div>

      ${sec("overview", "Overview")}
      <div class="card"><div class="set-title">Layout</div>
        <div class="set-sub">List shows every window per account. Dials show one ring per account, like the ring widgets.</div>
        ${segRow("layout", S.layout, [["list", "List"], ["dials", "Dials"]])}
        <div style="margin-top:6px">${toggleRow("Show subscription tier", "Plus, Pro, Max — beside the provider name in List", "toggle", "showTier", st.showTier)}</div>
        <div class="set-title rule-top" style="padding-top:12px;margin-top:4px">Credit accounts</div>
        <div class="set-sub">Normal lists the balances. Compact shows a count and the balance furthest below its threshold.</div>
        ${segRow("creditlayout", st.creditLayout, [["normal", "Normal"], ["compact", "Compact"]])}
        <div class="set-title">Show first</div><div class="set-sub">Which block sits at the top of Overview.</div>
        ${segRow("showfirst", st.showFirst, [["usage", "Usage providers"], ["credits", "Credits"]])}
        <div style="margin-top:6px">${toggleRow("Show credits", "Hide prepaid balances from Overview without removing them.", "toggle", "showCredits", st.showCredits)}</div>
        <button class="row-btn rule-top" data-act="push" data-arg="manage-credits"><span class="trow-text"><span class="trow-title">Manage credit accounts</span></span>${I.chevron}</button>
      </div>

      ${sec("notifications", "Notifications")}
      <div class="card"><div class="allowed">Notifications allowed</div></div>
      <div class="card" style="padding-top:2px;padding-bottom:2px">
        ${toggleRow(`${word} ${first} ${wordEnd}`, used ? "Once, the first time a limit exceeds four fifths used" : "Once, the first time a limit drops under a fifth remaining", "toggle", "notif.below20", n.below20)}
        ${toggleRow(`${word} ${second} ${wordEnd}`, `Once more when it gets tight, even after the ${first} warning`, "toggle", "notif.below10", n.below10)}
        ${toggleRow("Limit exhausted", "When a window is fully consumed", "toggle", "notif.exhausted", n.exhausted)}
        ${toggleRow("Reset credit available", "When Codex reports a usable rate-limit reset credit", "toggle", "notif.resetCredit", n.resetCredit)}
        ${toggleRow("Reset approaching", "About 30 minutes before a window rolls over", "toggle", "notif.approaching", n.approaching)}
        ${toggleRow("Unused reset credit expires soon", "Notify me when an unused Codex reset credit expires within 24 hours. A credit lets you reset an eligible usage limit.", "toggle", "notif.creditExpiring", n.creditExpiring)}
        ${toggleRow("Sign-in expired", "When an account needs to be reconnected", "toggle", "notif.authExpired", n.authExpired)}
      </div>

      ${sec("status-icons", "Status bar icons")}
      <div class="card" style="padding-top:2px">
        ${toggleRow("Show status bar icons", "A small icon with the account's figure next to the clock, updated after every refresh", "toggle", "statusIcons", st.statusIcons)}
        <div class="set-sub" style="margin:2px 0 8px">Status bar icons can be shown.</div>
        <div class="set-title rule-top" style="padding-top:10px;margin-top:0">Pinned accounts</div><div class="set-sub">${pinnedCount} of 3 pinned</div>
        ${pinRows}
        <div class="set-sub" style="margin:8px 0 0">Android decides how many icons fit. Notification settings control their visibility.</div>
      </div>

      ${sec("thresholds", "Thresholds")}
      <div class="card"><div class="set-sub" style="margin-top:0">Status is derived from the percentage still available.</div>
        <div class="thr"><span class="dot healthy"></span><span>Healthy</span><span>above 50%</span></div>
        <div class="thr"><span class="dot low"></span><span>Moderate</span><span>20–50%</span></div>
        <div class="thr"><span class="dot low"></span><span>Low</span><span>1–20%</span></div>
        <div class="thr"><span class="dot exhausted"></span><span>Exhausted</span><span>0%</span></div></div>

      ${sec("diagnostics", "Diagnostics")}
      <div class="card"><div class="inforow"><span>Accounts</span><span>${ACCOUNTS.length}</span></div>
        <div class="inforow"><span>Last successful sync</span><span>3m ago</span></div>
        <div class="inforow"><span>Last sync attempt</span><span>3m ago</span></div>
        <div class="inforow"><span>Widgets last repainted</span><span>3m ago</span></div>
        <div class="inforow"><span>Sync interval</span><span>${st.interval} min</span></div>
        <div class="fine" style="color:var(--faint)">Diagnostics deliberately exclude tokens, account identifiers and raw provider responses.</div></div>
      <div class="card"><div class="trow-title">Background refresh</div>
        <div class="set-sub">The widgets show what the last background refresh fetched. Android decides how often that runs; these are its current settings for this app.</div>
        <div class="inforow"><span>Background use</span><span>Allowed</span></div>
        <div class="inforow"><span>Battery optimisation</span><span>Off</span></div>
        <div class="inforow"><span>Standby bucket</span><span>Active</span></div>
        <button class="btn slim" style="margin-top:10px" data-act="toast" data-arg="In the app this opens Android’s battery settings.">Open battery settings</button></div>
      <button class="text-action" data-act="toast" data-arg="In the app this opens github.com/ZepiGit in the browser.">GitHub · ZepiGit</button>
    </div>${tabsHTML("settings")}</section>`;
  }

  function pushedTitle(title, sub) {
    return `<div class="head" style="padding-top:18px"><div class="head-text"><h1 class="title">${title}</h1>${sub ? `<p class="subtitle">${sub}</p>` : ""}</div></div>`;
  }

  function addAccountHTML() {
    const rows = ADD_PROVIDERS.map((p) => `<button class="pick" data-act="provider-signin" data-arg="${esc(p.name)}">
      ${p.logo ? logo(p.logo) : badge(p.badge)}<span style="flex:1"><span class="pick-name" style="display:block">${p.name}</span><span class="pick-sub" style="display:block">${p.hint}</span></span>${I.chevron}</button>`).join("");
    return `<section class="scr" data-screen="add-account"><div class="scroll">${pushedTitle("Add account", "You can connect several accounts per provider.")}
      <div class="card flush">${rows}</div>
      <p class="fine" style="margin-top:10px">Sign-in details stay on this device.</p></div></section>`;
  }

  function addCreditHTML() {
    const rows = ADD_CREDITS.map((p) => `<button class="pick" data-act="credit-connect" data-arg="${esc(p.name)}">
      ${p.logo ? logo(p.logo) : badge(p.badge)}<span style="flex:1;min-width:0"><span class="pick-name" style="display:block">${p.name}</span></span><span class="row-act">Open</span></button>`).join("");
    return `<section class="scr" data-screen="add-credit"><div class="scroll">${pushedTitle("Add credit balance")}
      <p class="fine" style="margin:0 4px 12px">Prepaid balances for API keys. The key is checked against the provider before it is saved and is stored only in this device's Keystore.</p>
      <div class="card flush">${rows}</div></div></section>`;
  }

  function manageCreditsHTML() {
    return `<section class="scr" data-screen="manage-credits"><div class="scroll">${pushedTitle("Manage credit accounts", `${CREDITS.length} balances`)}
      ${CREDITS.map(creditCardHTML).join("")}
      <button class="btn" style="margin-top:12px" data-act="push" data-arg="add-credit">Add credit balance</button></div></section>`;
  }

  function licencesHTML() {
    const fonts = [["Roboto Flex", "The Roboto Flex Project Authors"], ["Roboto Mono", "The Roboto Mono Project Authors"],
      ["IBM Plex Sans", "IBM Corp."], ["IBM Plex Mono", "IBM Corp."], ["Figtree", "The Figtree Project Authors"], ["JetBrains Mono", "The JetBrains Mono Project Authors"]];
    return `<section class="scr" data-screen="licences"><div class="scroll">${pushedTitle("Font licences")}
      <div class="card">${fonts.map(([f, a], i) => `<div class="lic${i ? " rule-top" : ""}" style="padding:9px 0"><b>${f}</b><br>Copyright ${a}. SIL Open Font License, Version 1.1.</div>`).join("")}</div>
      <p class="fine" style="margin-top:10px">The fonts ship inside the app; the full licence text opens from each entry in the app.</p></div></section>`;
  }

  function screenHTML(route) {
    const [name, id] = route.key.split(":");
    switch (name) {
      case "overview": return overviewHTML();
      case "resets": return resetsHTML();
      case "settings": return settingsHTML();
      case "account": return accountHTML(id);
      case "credit": return creditHTML(id);
      case "add-account": return addAccountHTML();
      case "add-credit": return addCreditHTML();
      case "manage-credits": return manageCreditsHTML();
      case "licences": return licencesHTML();
      default: return overviewHTML();
    }
  }

  // ───────────────────────── Home screen ─────────────────────────
  function homeHTML() {
    const pro = acct("claude-pro");
    const pw = soonest(pro);
    const ringsOrder = byNextReset();
    const wTrack = "rgba(255,255,255,0.22)";
    const wData = "#D97757";
    const ringRow = (a) => {
      const w = soonest(a);
      return `<button class="wr" data-act="widget-account" data-arg="${a.id}" aria-label="${esc(a.label)}, ${w.name}, ${shown(w.left)}% ${side()}">
        <span class="wr-ring">${ring(shown(w.left), { stroke: 9, track: wTrack, color: wData })}<b>${shown(w.left)}%</b></span>
        <span class="wr-text"><span class="wr-name">${acctLogo(a, "sm", { still: true })}${esc(a.label)}</span>
        <span class="wr-sub" style="display:block">${w.name} · ${widgetWhen(w)}</span></span></button>`;
    };
    const mini = (a) => {
      const w = soonest(a);
      return `<button class="wm" data-act="widget-account" data-arg="${a.id}" aria-label="${esc(a.label)}, ${shown(w.left)}% ${side()}">
        ${ring(shown(w.left), { stroke: 10, track: wTrack, color: wData })}${acctLogo(a, "", { still: true })}</button>`;
    };
    const creditsSorted = [...CREDITS].sort((x, y) => x.amount - y.amount);
    const shownCredits = creditsSorted.slice(0, 5);
    const more = CREDITS.length - shownCredits.length;
    const appIcon = `assets/icons/app-${S.set.appIcon}.webp`;
    return `<section class="scr home" data-screen="home"><div class="home-in">
      <div class="glance"><div class="glance-date">Tue, Nov 24</div></div>
      <div class="widgets">
        <div class="w w-summary"><span class="w-plate"></span>
          <button class="ws-main" data-act="widget-account" data-arg="claude-pro" style="display:flex;text-align:left" aria-label="Usage Summary: Personal, ${shown(pw.left)}% ${side()}">
            <span class="ws-name">${esc(pro.label)}</span><span class="ws-cap">${S.pct === "left" ? "Left" : "Used"} · ${pw.name}</span>
            <span class="ws-fig">${shown(pw.left)}%</span><span class="ws-bar"><i style="width:${shown(pw.left)}%"></i></span></button>
          <button class="ws-side" data-act="widget-overview" style="display:flex;text-align:left" aria-label="Open Overview">
            <span class="ws-cap">Account</span><span class="ws-status"><i></i>${WIDGET_STATUS[sev(headline(pro).left)]}</span></button>
          <button class="ws-refresh" data-act="widget-refresh" aria-label="Refresh usage">${I.refresh}</button>
        </div>
        <div class="w w-rings" role="group" aria-label="Account Rings widget"><span class="w-plate"></span>${ringsOrder.map(ringRow).join("")}</div>
        <div class="wrow">
          <div class="w w-mini" role="group" aria-label="Mini Rings widget"><span class="w-plate" id="mini-plate" style="opacity:${S.opacity / 100}"></span>${ringsOrder.map(mini).join("")}</div>
          <div class="w w-credits" role="group" aria-label="Credits widget"><span class="w-plate"></span>
            <button class="wc-head" data-act="widget-credits" style="display:flex;width:100%">Credits<span>As of 04:19</span></button>
            ${shownCredits.map((c) => `<button class="wc-row" data-act="widget-credit" data-arg="${c.id}" style="width:100%"><i class="${creditSev(c) ? "" : "healthy"}"></i><span>${esc(c.name)}</span><b>${money(c.amount)}</b></button>`).join("")}
            ${more > 0 ? `<button class="wc-more" data-act="widget-credits">+${more} more</button>` : ""}
          </div>
        </div>
      </div>
      <div class="dock">
        <div class="dock-row">
          <button class="app-ic" style="background:#dcefdc;color:#1e5b2a" data-act="dock" data-arg="Phone" aria-label="Phone">${I.phone}</button>
          <button class="app-ic" style="background:#d9e3fb;color:#26407a" data-act="dock" data-arg="Messages" aria-label="Messages">${I.msg}</button>
          <button class="app-ic ul" data-act="launch" aria-label="Open Usage Limits"><img src="${appIcon}" alt="" width="54" height="54"></button>
          <button class="app-ic" style="background:#f3e6d6;color:#6b4a2a" data-act="dock" data-arg="Browser" aria-label="Browser">${I.globe}</button>
          <button class="app-ic" style="background:#e4e2df;color:#3b3a38" data-act="dock" data-arg="Camera" aria-label="Camera">${I.camera}</button>
        </div>
        <button class="search" data-act="search" aria-label="Search"><span class="g">G</span><span class="grow"></span>${I.mic}${I.lens}</button>
      </div></div></section>`;
  }

  // ───────────────────────── Overlays ─────────────────────────
  function shadeHTML() {
    const tile = (k, icon, on) => `<button class="qs${on ? " on" : ""}" data-act="qs" aria-pressed="${on}" aria-label="${k}">${icon}</button>`;
    const rows = S.set.statusIcons && S.set.pinned.length
      ? `<div class="notifs">${S.set.pinned.map((id) => {
        const a = acct(id);
        const w = soonest(a);
        const out = w.left <= 0;
        const title = out ? `Exhausted<span> · ${esc(a.brand)}</span>` : `${shown(w.left)}% ${side()}<span> · ${esc(a.brand)}</span>`;
        const glyph = a.prov === "claude" ? '<svg style="width:12px;height:12px" viewBox="0 0 24 24"><use href="#p-claudecode"/></svg>' : `<svg style="width:12px;height:12px" viewBox="0 0 24 24"><use href="#p-${a.prov}"/></svg>`;
        return `<button class="notif" data-act="notif-open" data-arg="${id}"><span class="n-badge">${glyph}</span>
          <span class="n-text"><span class="n-title" style="display:block">${title}</span><span class="n-sub" style="display:block">${w.name} · Resets ${atLabel(w)}</span></span>
          <span class="n-icon">${acctLogo(a, "", { still: true })}</span></button>`;
      }).join("")}</div>`
      : `<div class="n-empty">No notifications</div>`;
    return `<div class="shade-clock">4:20</div><div class="shade-date">Tue 24 Nov</div>
      <div class="shade-tiles">${tile("Internet", I.wifi, true)}${tile("Bluetooth", I.bt, true)}${tile("Do not disturb", I.dnd, false)}${tile("Flashlight", I.torch, false)}</div>
      ${rows}<span class="shade-handle" aria-hidden="true"></span>`;
  }

  function recentsHTML() {
    if (S.appClosed) return `<div class="rec-empty">No recent items</div><div class="rec-actions"><button data-act="home">Back to Home</button></div>`;
    return `<div class="rec-label"><img src="assets/icons/app-${S.set.appIcon}.webp" alt="" width="24" height="24">Usage Limits</div>
      <div class="rec-card" role="button" tabindex="0" data-act="rec-open" aria-label="Return to Usage Limits"><div class="rec-thumb" inert aria-hidden="true">${screenHTML(top()).replace(/ data-act=/g, " data-x=").replace(/<button/g, "<span").replace(/<\/button>/g, "</span>")}</div></div>
      <div class="rec-actions"><button data-act="rec-clear">Clear all</button></div>`;
  }

  function openSheet(sheet) { S.sheet = sheet; renderOverlays(); }
  function closeSheet() { S.sheet = null; renderOverlays(); }
  function openDialog(d) { S.dialog = d; renderOverlays(); const b = els.dialog.querySelector("[data-act=dialog-ok]"); if (b) b.focus({ preventScroll: true }); }
  function closeDialog() { S.dialog = null; renderOverlays(); }

  let toastTimer = 0;
  function toast(text) {
    els.toast.textContent = text;
    els.toast.hidden = false;
    els.toast.classList.toggle("low", S.surface === "home" || !isTab(top().key));
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { els.toast.hidden = true; }, 2400);
  }

  function renderOverlays() {
    // Shade
    if (S.overlay === "shade") { els.shade.innerHTML = shadeHTML(); els.shade.hidden = false; } else { els.shade.hidden = true; els.shade.innerHTML = ""; }
    // Recents
    if (S.overlay === "recents") {
      els.recents.innerHTML = recentsHTML();
      els.recents.hidden = false;
      const sc = els.recents.querySelector(".rec-thumb .scroll");
      if (sc && top().scroll) sc.scrollTop = top().scroll;
    } else { els.recents.hidden = true; els.recents.innerHTML = ""; }
    // Sheet
    if (S.sheet) {
      const sh = S.sheet;
      const opts = sh.options ? sh.options.map((o) => `<button class="radio" role="radio" aria-checked="${o === sh.selected}" data-act="sheet-pick" data-arg="${esc(o)}"><i></i>${esc(o)}</button>`).join("") : "";
      els.sheet.innerHTML = `<div class="sheet-card" role="dialog" aria-modal="true" aria-label="${esc(sh.title)}"><div class="sheet-grip"></div><h3>${esc(sh.title)}</h3>
        ${sh.body ? `<p>${esc(sh.body)}</p>` : ""}${opts}<button class="btn" style="margin-top:10px" data-act="sheet-close">${sh.options ? "Done" : "Close"}</button></div>`;
      els.sheet.hidden = false;
    } else { els.sheet.hidden = true; els.sheet.innerHTML = ""; }
    // Dialog
    if (S.dialog) {
      const d = S.dialog;
      els.dialog.innerHTML = `<div class="dialog-card" role="alertdialog" aria-modal="true" aria-label="${esc(d.title)}"><h3>${esc(d.title)}</h3><p>${esc(d.body)}</p>
        <div class="dialog-actions"><button data-act="dialog-cancel">Cancel</button><button data-act="dialog-ok"${d.danger ? ' class="danger"' : ""}>${esc(d.confirm)}</button></div></div>`;
      els.dialog.hidden = false;
    } else { els.dialog.hidden = true; els.dialog.innerHTML = ""; }
    const wall = S.surface === "home" || S.overlay === "recents" || S.overlay === "shade";
    els.statusbar.classList.toggle("on-wall", wall);
    els.sysnav.classList.toggle("on-wall", S.surface === "home" || S.overlay === "recents");
    initFigures(els.lcd);
    renderGuide();
    syncControls();
    syncHash();
  }

  // ───────────────────────── Render ─────────────────────────
  const isTab = (key) => ["overview", "resets", "settings"].includes(key);

  function saveScroll() {
    const sc = els.view.querySelector(".scroll");
    if (rendered && sc) rendered.scroll = sc.scrollTop;
  }

  function render(transition) {
    saveScroll();
    els.lcd.dataset.theme = S.theme;
    document.documentElement.dataset.page = THEMES[S.theme].page;
    els.statusbar.innerHTML = statusBarHTML();
    const route = S.surface === "home" ? null : top();
    const key = route ? route.key : "home";
    els.view.innerHTML = route ? screenHTML(route) : homeHTML();
    const scr = els.view.firstElementChild;
    if (transition && !reduced() && scr) scr.classList.add(`enter-${transition}`);
    const sc = els.view.querySelector(".scroll");
    if (sc && route && route.scroll) sc.scrollTop = route.scroll;
    if (sc && route && route.jump) {
      const target = sc.querySelector(`[data-sec="${route.jump}"]`);
      route.jump = null;
      if (target) sc.scrollTop = Math.max(0, target.offsetTop - 30);
    }
    rendered = route;
    renderedKey = key;
    renderOverlays();
  }

  // Claude Code figures: pose every figure once, then animate the ones marked data-anim.
  let raf = 0;
  let clockStart = 0;
  function initFigures(root) {
    if (!window.ClaudeMotion) return;
    root.querySelectorAll(".cc[data-mood]:not([data-posed])").forEach((el) => {
      window.ClaudeMotion.applyPose(el, el.dataset.mood, 0.6, true);
      el.dataset.posed = "1";
    });
    if (!raf && root.querySelector(".cc[data-anim]")) {
      if (!clockStart) clockStart = performance.now();
      raf = requestAnimationFrame(tick);
    }
  }
  function tick(now) {
    const figs = els.lcd.querySelectorAll(".cc[data-anim]");
    if (!figs.length || reduced() || !S.set.animateClaude) { raf = 0; return; }
    const t = (now - clockStart) / 1000;
    figs.forEach((el) => {
      if (el.closest(".rec-thumb")) return;
      window.ClaudeMotion.applyPose(el, el.dataset.mood, t, false);
    });
    raf = requestAnimationFrame(tick);
  }

  // ───────────────────────── Navigation ─────────────────────────
  function enterApp() { S.surface = "app"; S.overlay = null; S.appClosed = false; }
  function openTab(tab) {
    const cur = top();
    if (S.surface === "app" && cur.key === tab && !S.overlay) {
      const sc = els.view.querySelector(".scroll");
      if (sc) sc.scrollTo({ top: 0, behavior: reduced() ? "auto" : "smooth" });
      return;
    }
    enterApp();
    S.stack = tab === "overview" ? [S.stack[0].key === "overview" ? S.stack[0] : { key: "overview" }] : [{ key: "overview" }, { key: tab }];
    S.sheet = null;
    render("fade");
  }
  function push(key, extra = {}) {
    enterApp();
    S.sheet = null;
    S.stack.push({ key, ...extra });
    render("push");
  }
  function back() {
    if (S.dialog) return closeDialog();
    if (S.sheet) return closeSheet();
    if (S.overlay) { S.overlay = null; return renderOverlays(); }
    if (S.surface === "home") { toast("You’re on the home screen. Tap a widget or the Usage Limits icon."); return; }
    if (S.stack.length > 1) {
      S.stack.pop();
      return render("pop");
    }
    goHome();
  }
  function goHome() {
    S.surface = "home";
    S.overlay = null;
    S.sheet = null;
    S.dialog = null;
    render("fade");
  }
  function openFromWidget(stack, jump) {
    enterApp();
    S.sheet = null;
    S.dialog = null;
    S.stack = stack.map((key) => ({ key }));
    if (jump) top().jump = jump;
    render("fade");
  }
  function recents() {
    if (S.overlay === "recents") {
      if (!S.appClosed) { S.overlay = null; S.surface = "app"; return render("fade"); }
      S.overlay = null; return renderOverlays();
    }
    S.sheet = null;
    S.dialog = null;
    saveScroll();
    S.overlay = "recents";
    renderOverlays();
  }

  // ───────────────────────── Actions ─────────────────────────
  function setTheme(theme) {
    if (!THEMES[theme]) return;
    S.theme = theme;
    render();
  }
  function setLayout(layout) {
    S.layout = layout;
    if (S.surface === "app" && top().key === "settings") return render();
    if (S.surface === "app" && top().key === "overview" && !S.overlay) return render();
    openTab("overview");
  }

  function act(name, arg, el) {
    switch (name) {
      case "tab": return openTab(arg);
      case "push": return push(arg);
      case "open-account": return push(`account:${arg}`);
      case "open-credit": return push(`credit:${arg}`);
      case "back": return back();
      case "home": return goHome();
      case "recents": return recents();
      case "shade":
        if (S.overlay === "shade") { S.overlay = null; return renderOverlays(); }
        S.overlay = "shade"; return renderOverlays();
      case "pct": S.pct = S.pct === "left" ? "used" : "left"; return render();
      case "pctmode": S.pct = arg; return render();
      case "filter": S.filter = arg; return render();
      case "refresh":
      case "widget-refresh": {
        if (!el || el.classList.contains("spin")) return;
        el.classList.add("spin");
        setTimeout(() => { el.classList.remove("spin"); toast("Refreshed. Demo figures stay the same."); }, 900);
        return;
      }
      case "acct-refresh": {
        if (!el || el.dataset.busy) return;
        el.dataset.busy = "1";
        el.textContent = "Refreshing…";
        setTimeout(() => {
          el.textContent = "Refresh now";
          delete el.dataset.busy;
          const u = els.view.querySelector("[data-updated]");
          if (u) u.textContent = "Updated just now";
          toast("Updated just now");
        }, 900);
        return;
      }
      case "reset-credit":
        return openDialog({
          title: "Use a reset credit?",
          body: "This consumes 1 reset credit and cannot be undone. Your rate limit will be reset immediately.",
          confirm: "Use credit",
          run: () => { S.codexReset = true; render(); toast("Limit reset applied"); },
        });
      case "acct-remove": {
        const a = acct(arg);
        return openDialog({
          title: "Remove account?",
          body: "Its stored credentials are deleted from this device. Nothing changes on the provider side, and you can add the account again at any time.",
          confirm: "Remove",
          danger: true,
          run: () => { toast(`Demo: ${fullName(a)} stays, nothing is deleted here.`); back(); },
        });
      }
      case "credit-remove":
        return openDialog({
          title: "Remove credit account?",
          body: "Its key is deleted from this device. It is not revoked at the provider: do that in the provider's console if the key may have leaked.",
          confirm: "Remove",
          danger: true,
          run: () => { toast("Demo: the credit account stays, nothing is deleted here."); back(); },
        });
      case "credit-save": return saveCredit(arg);
      case "acct-notif": S.acctNotif[arg] = S.acctNotif[arg] === false; return render();
      case "toggle": {
        if (arg.startsWith("notif.")) { const k = arg.slice(6); S.set.notif[k] = !S.set.notif[k]; }
        else S.set[arg] = !S.set[arg];
        if (arg === "showCredits" && !S.set.showCredits) S.creditsExpanded = false;
        return render();
      }
      case "pin": {
        if (!S.set.statusIcons) return toast("Turn on Show status bar icons first.");
        const p = S.set.pinned;
        const i = p.indexOf(arg);
        if (i >= 0) p.splice(i, 1);
        else if (p.length >= 3) return toast("Up to three accounts can be pinned. Unpin one first.");
        else p.push(arg);
        return render();
      }
      case "theme": return setTheme(arg);
      case "layout": return setLayout(arg);
      case "textfont":
        S.set.textFont = arg; render();
        if (arg !== "Roboto") toast("The app bundles this font; the preview keeps Roboto.");
        return;
      case "figfont":
        S.set.figureFont = arg; render();
        if (arg !== "Roboto Mono") toast("The app bundles this font; the preview keeps Roboto Mono.");
        return;
      case "interval": S.set.interval = Number(arg); return render();
      case "creditlayout": S.set.creditLayout = arg; S.creditsExpanded = false; return render();
      case "showfirst": S.set.showFirst = arg; return render();
      case "appicon": S.set.appIcon = arg; render(); return toast("Launcher icon changed. Press Home to see it in the dock.");
      case "expand-credits": S.creditsExpanded = !S.creditsExpanded; return render();
      case "choose-icon": {
        const c = ICON_CHOICES[arg];
        return openSheet({ title: `${c.name} icon`, options: c.options, selected: S.set.icons[arg], pick: (v) => { S.set.icons[arg] = v; } });
      }
      case "sheet-pick": {
        if (S.sheet && S.sheet.pick) { S.sheet.pick(arg); S.sheet.selected = arg; }
        S.sheet = null;
        return render();
      }
      case "sheet-close": return closeSheet();
      case "dialog-cancel": return closeDialog();
      case "dialog-ok": { const run = S.dialog && S.dialog.run; S.dialog = null; renderOverlays(); if (run) run(); return; }
      case "provider-signin":
        return openSheet({ title: `Add ${arg}`, body: `In the app this starts ${arg}'s own sign-in (browser, device code or API key). Sign-in details stay on this device. Nothing is sent from this demo.` });
      case "credit-connect": {
        const p = ADD_CREDITS.find((x) => x.name === arg);
        return openSheet({ title: `Connect ${arg}`, body: `${p ? p.hint + " " : ""}In the app you paste the key here; it is checked against the provider and stored only in the device Keystore. This demo does not ask for keys.` });
      }
      case "toast": return toast(arg);
      case "widget-account": return openFromWidget(["overview", `account:${arg}`]);
      case "widget-overview": return openFromWidget(["overview"]);
      case "widget-credits": return openFromWidget(["overview"], "credits");
      case "widget-credit": return openFromWidget(["overview", `credit:${arg}`]);
      case "launch":
        if (S.appClosed || !S.stack.length) S.stack = [{ key: "overview" }];
        enterApp();
        return render("fade");
      case "dock": return toast(`${arg} is not part of this demo. Usage Limits is the app in the middle.`);
      case "search": return toast("Search is not part of this demo.");
      case "qs": {
        const on = !el.classList.contains("on");
        el.classList.toggle("on", on);
        el.setAttribute("aria-pressed", String(on));
        return;
      }
      case "notif-open": S.overlay = null; return openFromWidget(["overview", `account:${arg}`]);
      case "rec-open": S.overlay = null; S.surface = "app"; return render("fade");
      case "rec-clear":
        S.stack = [{ key: "overview" }];
        S.appClosed = true;
        S.overlay = null;
        S.surface = "home";
        return render("fade");
      case "jump": {
        if (S.surface !== "app" || top().key !== "settings" || S.overlay) {
          enterApp();
          S.stack = [{ key: "overview" }, { key: "settings", jump: arg }];
          return render("fade");
        }
        const sc = els.view.querySelector(".scroll");
        const target = sc && sc.querySelector(`[data-sec="${arg}"]`);
        if (target) sc.scrollTo({ top: Math.max(0, target.offsetTop - 30), behavior: reduced() ? "auto" : "smooth" });
        return;
      }
      default:
        return undefined;
    }
  }

  function saveCredit(id) {
    const c = credit(id);
    const name = els.view.querySelector("#cd-name").value.trim();
    const raw = els.view.querySelector("#cd-threshold").value.trim().replace(",", ".");
    let threshold = 5;
    if (raw) {
      const v = Number(raw);
      if (!Number.isFinite(v) || v <= 0) {
        const help = els.view.querySelector("#cd-help");
        help.textContent = "Enter a number above zero, or leave blank for the default.";
        help.style.color = "var(--negtext)";
        return;
      }
      threshold = v;
    }
    c.name = name || c.source;
    c.threshold = threshold;
    render();
    toast("Saved");
  }

  // ───────────────────────── Context panel ─────────────────────────
  const BACK_HINT = "Back (◁) in the phone’s navigation bar returns to the previous screen; Home (○) goes to the home screen.";
  function guideFor() {
    if (S.overlay === "shade") return {
      title: "Notification shade",
      body: "Pinned accounts show here as quiet ongoing notifications: the figure, the window and its next reset — the same ones drawn next to the clock. Pin up to three in Settings › Status bar icons.",
      hint: "Tap a notification to open that account. Back, a swipe up or the status bar closes the shade.",
      links: [["Status bar icon settings", "jump", "status-icons"]],
    };
    if (S.overlay === "recents") return {
      title: "Recent apps",
      body: S.appClosed ? "No apps are open. Clear all closed Usage Limits; its back stack starts over next time." : "Android’s overview of open apps, showing Usage Limits where you left it.",
      hint: S.appClosed ? "Press Home or Back to return." : "Tap the card to go back to it, or Clear all to close the app. Recents again switches back.",
      links: [],
    };
    if (S.surface === "home") return {
      title: "Home screen and widgets",
      body: "Four of the app’s widgets: Usage Summary, Account Rings (soonest reset first), Mini Rings and Credits (lowest balance first). Each widget has its own panel colour and opacity; Mini Rings is set to 70% so the wallpaper shows through (the app default is 100%).",
      hint: "Drag the Mini Rings background opacity slider to change its plate. Tap a ring for that account, a credit for its balance, or the Usage Limits icon in the dock to open the app where you left it.",
      links: [["Open the app", "launch"], ["Notification shade", "shade"]],
    };
    const [name, id] = top().key.split(":");
    switch (name) {
      case "overview":
        if (S.layout === "dials") return {
          title: "Overview · Dials",
          body: "One ring per account showing its headline — the window with the least left — like the ring widgets. Exhausted accounts sort first; prepaid credits follow below.",
          hint: "Tap a dial for every window and its resets. The dashed tile adds an account.",
          links: [["OpenAI Codex detail", "open-account", "codex-plus"], ["Resets", "tab", "resets"], ["Settings", "tab", "settings"]],
        };
        return {
          title: "Overview · List",
          body: "Every subscription with each usage window: a bar, how much is left and the next reset. The emphasised line is the account’s headline; accounts sort by it, so Claude Max 5x and OpenAI Codex Plus (both exhausted) lead. Prepaid API credits follow further down.",
          hint: "Tap any account for its detail. Try the filters, the % left / % used chip, or scroll to Credits.",
          links: [["OpenAI Codex detail", "open-account", "codex-plus"], ["Resets", "tab", "resets"], ["Settings", "tab", "settings"]],
        };
      case "account": {
        const a = acct(id);
        if (id === "codex-plus") return S.codexReset ? {
          title: "OpenAI Codex Plus",
          body: "The reset credit is spent: the weekly window started over at 100% left, the account is Healthy again and Reset credits shows None available.",
          hint: BACK_HINT,
          links: [],
        } : {
          title: "OpenAI Codex Plus",
          body: "The weekly window is exhausted, but Codex reports a reset credit: 1 available, expiring in 28d 18h. Reset limit asks for confirmation and then starts the weekly window over.",
          hint: "Tap Reset limit to use it. " + BACK_HINT,
          links: [],
        };
        return {
          title: fullName(a),
          body: `Every window of this ${a.brand} account with what is left and when it resets, plus its own notification switch. Refresh now asks the provider again; Remove account only deletes the sign-in stored on the device.`,
          hint: (a.prov === "claude" ? "The Claude Code figure moves with the account’s usage, as in the app. " : "") + BACK_HINT,
          links: [],
        };
      }
      case "credit": return {
        title: `${credit(id).name} · credit balance`,
        body: "A prepaid API balance read with your own key, straight from the provider. Below the low-balance figure the balance reads Low and the widget marks it.",
        hint: "Change the name or the figure and tap Save. " + BACK_HINT,
        links: [],
      };
      case "resets": return {
        title: "Resets",
        body: "Every upcoming reset across all accounts, grouped by day and soonest first. The marked row is the next one; the right column counts down.",
        hint: "Tap a row to open that account.",
        links: [["Overview", "tab", "overview"], ["Settings", "tab", "settings"]],
      };
      case "settings": return {
        title: "Settings",
        body: "One scrolling list, as in the app: Appearance, Provider icons, App icon, Sync, Display, Overview, Notifications, Status bar icons, Thresholds and Diagnostics. The switches work: themes recolour the app, Display and Overview reshape the Overview, pinned accounts change the status bar.",
        hint: "Jump to a section:",
        links: SETTINGS_SECTIONS.map(([k, l]) => [l, "jump", k]),
      };
      case "manage-credits": return { title: "Manage credit accounts", body: "Every prepaid balance in one place, including ones hidden from Overview.", hint: "Tap a balance to edit it. " + BACK_HINT, links: [] };
      case "add-account": return { title: "Add account", body: "The supported subscriptions. Each one signs in with the provider’s own flow — browser, device code or API key — and the credentials stay on the device.", hint: "Tap a provider to see how it connects. " + BACK_HINT, links: [] };
      case "add-credit": return { title: "Add credit balance", body: "Prepaid API balances. The key is checked against the provider before it is saved and is stored only in the device Keystore.", hint: "Tap a provider for the kind of key it needs. " + BACK_HINT, links: [] };
      case "licences": return { title: "Font licences", body: "The app bundles its text and figure fonts under the SIL Open Font License.", hint: BACK_HINT, links: [] };
      default: return { title: "Usage Limits", body: "", hint: "", links: [] };
    }
  }

  function renderGuide() {
    const g = guideFor();
    const sig = `${g.title}|${g.body}|${g.hint}|${g.links.map((l) => l.join()).join()}`;
    if (els.guide.dataset.sig === sig) return;
    const apply = () => {
      els.guideTitle.textContent = g.title;
      els.guideBody.textContent = g.body;
      els.guideHint.textContent = g.hint;
      els.guideHint.hidden = !g.hint;
      els.guideLinks.innerHTML = g.links.map(([l, a, x]) => `<button type="button" data-gact="${a}" data-garg="${esc(x || "")}">${esc(l)}</button>`).join("");
      els.guide.classList.remove("is-updating");
    };
    const first = !els.guide.dataset.sig;
    els.guide.dataset.sig = sig;
    if (first || reduced()) return apply();
    els.guide.classList.add("is-updating");
    clearTimeout(renderGuide.t);
    renderGuide.t = setTimeout(apply, 150);
  }

  function syncControls() {
    const home = S.surface === "home" && !S.overlay;
    els.homeBtn.setAttribute("aria-pressed", String(home));
    els.opacity.hidden = S.surface !== "home";
    for (const b of els.layoutSeg.querySelectorAll("button")) b.setAttribute("aria-pressed", String(b.dataset.layout === S.layout));
    for (const b of els.themeSeg.querySelectorAll("button")) b.setAttribute("aria-pressed", String(b.dataset.theme === S.theme));
  }

  function setOpacity(v) {
    S.opacity = Math.max(0, Math.min(100, Math.round(Number(v))));
    const plate = els.view.querySelector("#mini-plate");
    if (plate) plate.style.opacity = String(S.opacity / 100);
    els.opacityOut.textContent = S.opacity === 0 ? "clear" : `${S.opacity}%`;
    if (Number(els.opacitySlider.value) !== S.opacity) els.opacitySlider.value = String(S.opacity);
  }

  // ───────────────────────── Hash deep links ─────────────────────────
  function hashFor() {
    if (S.overlay === "shade") return "shade";
    if (S.surface === "home") return "home";
    const [name, id] = top().key.split(":");
    if (name === "overview") return S.layout === "dials" ? "overview/dials" : "overview";
    if (name === "account" || name === "credit") return `${name}/${id}`;
    return name;
  }
  function syncHash() {
    const h = `#${hashFor()}`;
    if (location.hash !== h) history.replaceState(null, "", h);
  }
  function applyHash() {
    const raw = decodeURIComponent((location.hash || "").replace(/^#/, ""));
    if (!raw || raw === hashFor()) return false;
    const [name, sub] = raw.split("/");
    S.overlay = null;
    S.sheet = null;
    S.dialog = null;
    if (name === "home") { S.surface = "home"; return true; }
    if (name === "shade") { S.overlay = "shade"; return true; }
    enterApp();
    if (name === "overview") { if (sub === "dials" || sub === "list") S.layout = sub; S.stack = [{ key: "overview" }]; if (sub === "credits") top().jump = "credits"; return true; }
    if (name === "resets") { S.stack = [{ key: "overview" }, { key: "resets" }]; return true; }
    if (name === "settings") { S.stack = [{ key: "overview" }, { key: "settings", jump: sub || null }]; return true; }
    if (name === "account" && acct(sub)) { S.stack = [{ key: "overview" }, { key: `account:${sub}` }]; return true; }
    if (name === "credit" && credit(sub)) { S.stack = [{ key: "overview" }, { key: `credit:${sub}` }]; return true; }
    if (["add-account", "add-credit"].includes(name)) { S.stack = [{ key: "overview" }, { key: name }]; return true; }
    if (["manage-credits", "licences"].includes(name)) { S.stack = [{ key: "overview" }, { key: "settings" }, { key: name }]; return true; }
    return false;
  }

  // ───────────────────────── Input ─────────────────────────
  let swipedAt = 0;
  function bindGestures() {
    let start = null;
    const point = (ev) => {
      const r = els.lcd.getBoundingClientRect();
      const s = r.width / 360;
      return { x: (ev.clientX - r.left) / s, y: (ev.clientY - r.top) / s };
    };
    els.lcd.addEventListener("pointerdown", (ev) => {
      if (ev.button) return;
      const p = point(ev);
      start = { ...p, inShade: !!ev.target.closest(".shade"), onCard: !!ev.target.closest(".rec-card") };
    }, { passive: true });
    els.lcd.addEventListener("pointercancel", () => { start = null; });
    els.lcd.addEventListener("pointerup", (ev) => {
      if (!start) return;
      const p = point(ev);
      const dx = p.x - start.x, dy = p.y - start.y;
      const s0 = start;
      start = null;
      if (Math.hypot(dx, dy) < 36) return;
      if (s0.inShade && dy < -40 && Math.abs(dy) > Math.abs(dx)) { swipedAt = Date.now(); S.overlay = null; return renderOverlays(); }
      if (s0.onCard && dy < -60 && Math.abs(dy) > Math.abs(dx)) { swipedAt = Date.now(); return act("rec-clear"); }
      if (s0.y < 40 && dy > 40 && Math.abs(dy) > Math.abs(dx) && S.overlay !== "shade") { swipedAt = Date.now(); S.overlay = "shade"; return renderOverlays(); }
      if (s0.x < 22 && dx > 50 && Math.abs(dx) > Math.abs(dy)) { swipedAt = Date.now(); return back(); }
    });
  }

  function bindClicks() {
    els.lcd.addEventListener("click", (ev) => {
      if (Date.now() - swipedAt < 400) { ev.preventDefault(); return; }
      const t = ev.target.closest("[data-act]");
      if (t && els.lcd.contains(t)) {
        ev.preventDefault();
        act(t.dataset.act, t.dataset.arg, t);
        return;
      }
      // Tapping the scrim closes a sheet / dialog / shade.
      if (ev.target === els.sheet) return closeSheet();
      if (ev.target === els.dialog) return closeDialog();
      if (S.overlay === "shade" && !ev.target.closest(".notif, .qs")) { S.overlay = null; renderOverlays(); }
      if (S.overlay === "recents" && ev.target === els.recents) recents();
    });
    els.lcd.addEventListener("keydown", (ev) => {
      if ((ev.key === "Enter" || ev.key === " ") && ev.target.matches('[role="button"][data-act]')) {
        ev.preventDefault();
        act(ev.target.dataset.act, ev.target.dataset.arg, ev.target);
        return;
      }
      if (ev.key === "Enter" && ev.target.matches("#cd-name, #cd-threshold")) {
        ev.preventDefault();
        const b = els.view.querySelector("[data-act=credit-save]");
        if (b) act("credit-save", b.dataset.arg, b);
      }
    });
    document.addEventListener("keydown", (ev) => {
      if (ev.key === "Escape") { ev.preventDefault(); back(); }
    });
    els.guideLinks.addEventListener("click", (ev) => {
      const b = ev.target.closest("button[data-gact]");
      if (b) act(b.dataset.gact, b.dataset.garg || undefined, null);
    });
    els.homeBtn.addEventListener("click", () => goHome());
    els.layoutSeg.addEventListener("click", (ev) => { const b = ev.target.closest("button[data-layout]"); if (b) setLayout(b.dataset.layout); });
    els.themeSeg.addEventListener("click", (ev) => { const b = ev.target.closest("button[data-theme]"); if (b) setTheme(b.dataset.theme); });
    els.opacitySlider.addEventListener("input", (ev) => setOpacity(ev.target.value));
    window.addEventListener("hashchange", () => { if (applyHash()) render("fade"); });
  }

  function fit() {
    const w = els.screen.clientWidth;
    if (w) els.lcd.style.setProperty("--s", String(w / 360));
  }

  function init() {
    const $ = (id) => document.getElementById(id);
    Object.assign(els, {
      screen: $("screen"), lcd: $("lcd"), view: $("view"), statusbar: $("statusbar"), sysnav: $("sysnav"),
      shade: $("shade"), recents: $("recents"), sheet: $("sheet"), dialog: $("dialog"), toast: $("toast"),
      guide: $("guide"), guideTitle: $("guide-title"), guideBody: $("guide-body"), guideHint: $("guide-hint"), guideLinks: $("guide-links"),
      homeBtn: $("home-btn"), layoutSeg: $("layout-toggle"), themeSeg: $("theme-toggle"),
      opacity: $("opacity-ctrl"), opacitySlider: $("opacity-slider"), opacityOut: $("opacity-value"),
    });
    S.theme = window.matchMedia("(prefers-color-scheme: light)").matches ? "light-paper" : "pure-black";
    fit();
    if ("ResizeObserver" in window) new ResizeObserver(fit).observe(els.screen);
    else window.addEventListener("resize", fit);
    applyHash();
    setOpacity(S.opacity);
    bindGestures();
    bindClicks();
    window.matchMedia("(prefers-reduced-motion: reduce)").addEventListener("change", () => render());
    render();
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
  else init();
})();
