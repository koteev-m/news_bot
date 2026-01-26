export type ThemeMode = "light" | "dark";

export interface UIPreferences {
  theme: ThemeMode;
  lastTab: string;
  portfolioId: string;
}

let jwt: string | null = null;

const PREF_STORAGE_KEY = "portfolio-miniapp:prefs";
const DEFAULT_PREFS: UIPreferences = {
  theme: "light",
  lastTab: "/",
  portfolioId: "",
};

let cachedPrefs: UIPreferences = { ...DEFAULT_PREFS };

function readPrefsFromStorage(): UIPreferences {
  if (typeof window === "undefined") {
    return cachedPrefs;
  }
  try {
    const raw = window.localStorage.getItem(PREF_STORAGE_KEY);
    if (!raw) {
      return cachedPrefs;
    }
    const parsed = JSON.parse(raw) as Partial<UIPreferences>;
    return {
      theme: parsed.theme === "dark" ? "dark" : "light",
      lastTab: typeof parsed.lastTab === "string" ? parsed.lastTab : DEFAULT_PREFS.lastTab,
      portfolioId: typeof parsed.portfolioId === "string" ? parsed.portfolioId : DEFAULT_PREFS.portfolioId,
    };
  } catch (error) {
    console.warn("Failed to parse UI preferences", error);
    return cachedPrefs;
  }
}

function persistPrefs(next: UIPreferences): void {
  cachedPrefs = next;
  if (typeof window === "undefined") {
    return;
  }
  try {
    window.localStorage.setItem(PREF_STORAGE_KEY, JSON.stringify(next));
  } catch (error) {
    console.warn("Failed to persist UI preferences", error);
  }
}

export function loadUIPreferences(): UIPreferences {
  const prefs = readPrefsFromStorage();
  cachedPrefs = prefs;
  return prefs;
}

export function saveUIPreferences(next: UIPreferences): void {
  persistPrefs(next);
}

export function getUIPreferences(): UIPreferences {
  return cachedPrefs;
}

export function setThemePreference(theme: ThemeMode): void {
  const next: UIPreferences = {
    ...cachedPrefs,
    theme
  };
  persistPrefs(next);
}

export function setLastTabPreference(path: string): void {
  const next: UIPreferences = {
    ...cachedPrefs,
    lastTab: path
  };
  persistPrefs(next);
}

export function getPortfolioIdPreference(): string {
  return cachedPrefs.portfolioId;
}

export function setPortfolioIdPreference(portfolioId: string): void {
  const next: UIPreferences = {
    ...cachedPrefs,
    portfolioId
  };
  persistPrefs(next);
}

export function clearUIPreferences(): void {
  cachedPrefs = { ...DEFAULT_PREFS };
  if (typeof window === "undefined") {
    return;
  }
  try {
    window.localStorage.removeItem(PREF_STORAGE_KEY);
  } catch (error) {
    console.warn("Failed to clear UI preferences", error);
  }
}

export function setJwt(value: string): void {
  jwt = value;
}

export function getJwt(): string | null {
  return jwt;
}

export function clearJwt(): void {
  jwt = null;
}
