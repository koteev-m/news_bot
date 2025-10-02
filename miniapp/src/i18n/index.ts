import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import LanguageDetector from "i18next-browser-languagedetector";
import enCommon from "../locales/en/common.json";
import ruCommon from "../locales/ru/common.json";

type SupportedLocale = "en" | "ru";

const STORAGE_KEY = "locale";
const FALLBACK_LOCALE: SupportedLocale = "en";

const resources = {
  en: {
    common: enCommon,
  },
  ru: {
    common: ruCommon,
  },
} as const;

function normalizeLocale(value: string | null | undefined): SupportedLocale | null {
  if (!value) {
    return null;
  }
  const lower = value.toLowerCase();
  if (lower.startsWith("ru")) {
    return "ru";
  }
  if (lower.startsWith("en")) {
    return "en";
  }
  return null;
}

function readStoredLocale(): SupportedLocale | null {
  if (typeof window === "undefined") {
    return null;
  }
  try {
    const value = window.localStorage.getItem(STORAGE_KEY);
    return normalizeLocale(value);
  } catch (_error) {
    return null;
  }
}

function readTelegramLocale(): SupportedLocale | null {
  const telegram = (globalThis as typeof globalThis & {
    Telegram?: {
      WebApp?: {
        initDataUnsafe?: {
          user?: {
            language_code?: string;
          };
        };
      };
    };
  }).Telegram;
  if (!telegram?.WebApp?.initDataUnsafe?.user?.language_code) {
    return null;
  }
  return normalizeLocale(telegram.WebApp.initDataUnsafe.user.language_code);
}

function readNavigatorLocale(): SupportedLocale | null {
  if (typeof navigator === "undefined") {
    return null;
  }
  return normalizeLocale(navigator.language);
}

export function detectLocale(): SupportedLocale {
  const telegramLocale = readTelegramLocale();
  if (telegramLocale) {
    return telegramLocale;
  }
  const navigatorLocale = readNavigatorLocale();
  if (navigatorLocale) {
    return navigatorLocale;
  }
  const storedLocale = readStoredLocale();
  if (storedLocale) {
    return storedLocale;
  }
  return FALLBACK_LOCALE;
}

export function getInitialLocale(): SupportedLocale {
  const storedLocale = readStoredLocale();
  if (storedLocale) {
    return storedLocale;
  }
  return detectLocale();
}

const languageDetector = new LanguageDetector();

languageDetector.addDetector({
  name: "miniappLocale",
  lookup: () => getInitialLocale(),
  cacheUserLanguage: () => undefined,
});

void i18n
  .use(languageDetector)
  .use(initReactI18next)
  .init({
    resources,
    fallbackLng: FALLBACK_LOCALE,
    defaultNS: "common",
    ns: ["common"],
    keySeparator: false,
    interpolation: {
      escapeValue: false,
    },
    detection: {
      order: ["miniappLocale"],
      caches: [],
    },
  });

export { i18n };
export type Locale = SupportedLocale;
export const supportedLocales: SupportedLocale[] = ["en", "ru"];
export const localeStorageKey = STORAGE_KEY;
