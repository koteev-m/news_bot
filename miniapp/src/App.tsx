import { useCallback, useEffect, useMemo, useRef } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { TopBar } from "./components/TopBar";
import { TabBar } from "./components/TabBar";
import { AppRoutes } from "./router";
import { parseStartParam } from "./lib/telegram";
import { getUIPreferences, setLastTabPreference, setThemePreference } from "./lib/session";
import type { ThemeMode } from "./lib/session";
import type { InitDataUnsafe } from "./lib/telegram";
import { ErrorBoundary } from "./components/ErrorBoundary";
import { ToasterProvider } from "./components/Toaster";
import { SkipLink } from "./components/SkipLink";

type TabDefinition = {
  path: string;
  labelKey: string;
  titleKey: string;
};

const TAB_DEFINITIONS: TabDefinition[] = [
  { path: "/", labelKey: "nav.dashboard", titleKey: "dashboard.heading" },
  { path: "/positions", labelKey: "nav.positions", titleKey: "nav.positions" },
  { path: "/trades", labelKey: "nav.trades", titleKey: "nav.trades" },
  { path: "/import", labelKey: "nav.import", titleKey: "import.title" },
  { path: "/calendar", labelKey: "nav.calendar", titleKey: "calendar.title" },
  { path: "/reports", labelKey: "nav.reports", titleKey: "reports.title" },
  { path: "/settings", labelKey: "nav.settings", titleKey: "settings.title" },
];

function mapTabToPath(tab: string | undefined, labels: Array<{ path: string; label: string }>): string | null {
  if (!tab) {
    return null;
  }
  const lower = tab.toLowerCase();
  const item = labels.find((entry) => entry.label.toLowerCase() === lower || entry.path.replace("/", "") === lower);
  return item ? item.path : null;
}

interface AppProps {
  theme: ThemeMode;
  onThemeChange: (theme: ThemeMode) => void;
  initDataUnsafe: InitDataUnsafe | null;
}

export function App({ theme, onThemeChange, initDataUnsafe }: AppProps): JSX.Element {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const initialisedRef = useRef(false);

  const tabs = useMemo(
    () =>
      TAB_DEFINITIONS.map((definition) => ({
        path: definition.path,
        label: t(definition.labelKey),
        title: t(definition.titleKey),
      })),
    [t],
  );

  useEffect(() => {
    if (initialisedRef.current) {
      return;
    }
    initialisedRef.current = true;
    const start = parseStartParam();
    const target = mapTabToPath(start.tab, tabs);
    const stored = getUIPreferences().lastTab || "/";
    const desired = target ?? stored;
    if (desired !== location.pathname) {
      navigate(desired, { replace: true });
    }
  }, [location.pathname, navigate, tabs]);

  useEffect(() => {
    setLastTabPreference(location.pathname);
  }, [location.pathname]);

  const activeTab = useMemo(() => tabs.find((tab) => tab.path === location.pathname) ?? tabs[0], [tabs, location.pathname]);

  const handleToggleTheme = useCallback(() => {
    const nextTheme: ThemeMode = theme === "dark" ? "light" : "dark";
    setThemePreference(nextTheme);
    onThemeChange(nextTheme);
  }, [theme, onThemeChange]);

  const renderError = useCallback(
    (_error: Error, onRetry: () => void) => (
      <div className="page">
        <section className="card" role="alert" aria-live="assertive">
          <h2>{t("error.generic")}</h2>
          <button type="button" onClick={onRetry} className="top-bar__button">
            {t("error.retry")}
          </button>
        </section>
      </div>
    ),
    [t],
  );

  return (
    <ToasterProvider>
      <SkipLink />
      <ErrorBoundary renderError={renderError}>
        <div className="app-shell">
          <TopBar title={activeTab?.title ?? t("app.title")} theme={theme} onToggleTheme={handleToggleTheme} />
          <main className="app-content" id="main-content" tabIndex={-1}>
            <AppRoutes
              settingsProps={{
                theme,
                onThemeChange: (nextTheme) => {
                  setThemePreference(nextTheme);
                  onThemeChange(nextTheme);
                },
                initDataUnsafe,
              }}
            />
          </main>
          <TabBar
            items={tabs.map(({ path, label }) => ({ path, label }))}
            activePath={activeTab.path}
            onNavigate={(path) => {
              if (path !== location.pathname) {
                navigate(path);
              }
            }}
            ariaLabel={t("app.title")}
          />
        </div>
      </ErrorBoundary>
    </ToasterProvider>
  );
}
