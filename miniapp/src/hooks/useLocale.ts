import { useCallback, useEffect, useState } from "react";
import { getInitialLocale, i18n, localeStorageKey, type Locale } from "../i18n";

function coerceLocale(value: string | undefined): Locale {
  if (!value) {
    return "en";
  }
  const lower = value.toLowerCase();
  if (lower.startsWith("ru")) {
    return "ru";
  }
  return "en";
}

function persistLocale(locale: Locale): void {
  if (typeof window === "undefined") {
    return;
  }
  try {
    window.localStorage.setItem(localeStorageKey, locale);
  } catch (_error) {
    // ignore storage errors (private browsing, etc.)
  }
}

export function useLocale(): { locale: Locale; setLocale: (locale: Locale) => void } {
  const [locale, setLocaleState] = useState<Locale>(() => coerceLocale(i18n.language) ?? getInitialLocale());

  useEffect(() => {
    document.documentElement.lang = locale;
    persistLocale(locale);
  }, [locale]);

  useEffect(() => {
    const handleLanguageChange = (next: string): void => {
      setLocaleState(coerceLocale(next));
    };
    i18n.on("languageChanged", handleLanguageChange);
    return () => {
      i18n.off("languageChanged", handleLanguageChange);
    };
  }, []);

  useEffect(() => {
    if (coerceLocale(i18n.language) !== locale) {
      void i18n.changeLanguage(locale);
    }
  }, [locale]);

  const setLocale = useCallback(
    (nextLocale: Locale) => {
      setLocaleState(nextLocale);
      void i18n.changeLanguage(nextLocale);
    },
    [],
  );

  return { locale, setLocale };
}

export function getInitialLocaleValue(): Locale {
  const initial = getInitialLocale();
  document.documentElement.lang = initial;
  return initial;
}
