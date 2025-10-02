import "./i18n";
import "./styles.css";
import "./styles.a11y.css";

import { StrictMode, useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { App } from "./App";
import { getInitialLocaleValue } from "./hooks/useLocale";
import { AuthRequired, useAuthGuard } from "./lib/guards";
import { getInitData, getInitDataUnsafe, getWebApp, ready, expand } from "./lib/telegram";
import type { InitDataUnsafe } from "./lib/telegram";
import { loadUIPreferences } from "./lib/session";
import type { ThemeMode } from "./lib/session";

getInitialLocaleValue();

function applyReducedMotion(matches: boolean): void {
  document.documentElement.classList.toggle("reduce-motion", matches);
}

function Root(): JSX.Element {
  const { t } = useTranslation();
  const [theme, setTheme] = useState<ThemeMode>(() => loadUIPreferences().theme);
  const [initData, setInitData] = useState<string | null>(null);
  const [initDataUnsafe, setInitDataUnsafe] = useState<InitDataUnsafe | null>(null);
  const [initReady, setInitReady] = useState(false);

  useEffect(() => {
    const webApp = getWebApp();
    if (webApp) {
      ready();
      expand();
      const data = getInitData();
      setInitData(data || null);
      setInitDataUnsafe(getInitDataUnsafe());
    } else {
      setInitData(null);
      setInitDataUnsafe(null);
    }
    setInitReady(true);
  }, []);

  useEffect(() => {
    document.documentElement.setAttribute("data-theme", theme);
  }, [theme]);

  useEffect(() => {
    if (typeof window === "undefined") {
      return;
    }
    const query = window.matchMedia("(prefers-reduced-motion: reduce)");
    applyReducedMotion(query.matches);
    const listener = (event: MediaQueryListEvent) => applyReducedMotion(event.matches);
    query.addEventListener("change", listener);
    return () => {
      query.removeEventListener("change", listener);
    };
  }, []);

  const authState = useAuthGuard(initData, initReady);

  const content = useMemo(() => {
    if (authState.status === "pending") {
      return <div className="loading-screen">{t("loading")}</div>;
    }
    if (authState.status === "unauthorized") {
      return <AuthRequired error={authState.error} />;
    }
    return (
      <BrowserRouter>
        <App theme={theme} onThemeChange={setTheme} initDataUnsafe={initDataUnsafe} />
      </BrowserRouter>
    );
  }, [authState, theme, initDataUnsafe, t]);

  return content;
}

const container = document.getElementById("root");
if (!container) {
  throw new Error("Root container not found");
}

const root = createRoot(container);
root.render(
  <StrictMode>
    <Root />
  </StrictMode>,
);
