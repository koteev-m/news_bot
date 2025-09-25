import { useEffect, useMemo, useRef } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { TopBar } from "./components/TopBar";
import { TabBar } from "./components/TabBar";
import { AppRoutes } from "./router";
import { parseStartParam } from "./lib/telegram";
import { getUIPreferences, setLastTabPreference, setThemePreference } from "./lib/session";
import type { ThemeMode } from "./lib/session";
import type { InitDataUnsafe } from "./lib/telegram";

const tabs = [
  { path: "/", label: "Dashboard", title: "Dashboard" },
  { path: "/positions", label: "Positions", title: "Positions" },
  { path: "/trades", label: "Trades", title: "Trades" },
  { path: "/import", label: "Import", title: "Import" },
  { path: "/calendar", label: "Calendar", title: "Calendar" },
  { path: "/reports", label: "Reports", title: "Reports" },
  { path: "/settings", label: "Settings", title: "Settings" }
];

function mapTabToPath(tab?: string): string | null {
  if (!tab) {
    return null;
  }
  const lower = tab.toLowerCase();
  const item = tabs.find((entry) => entry.label.toLowerCase() === lower || entry.path.replace("/", "") === lower);
  return item ? item.path : null;
}

interface AppProps {
  theme: ThemeMode;
  onThemeChange: (theme: ThemeMode) => void;
  initDataUnsafe: InitDataUnsafe | null;
}

export function App({ theme, onThemeChange, initDataUnsafe }: AppProps): JSX.Element {
  const location = useLocation();
  const navigate = useNavigate();
  const initialisedRef = useRef(false);

  useEffect(() => {
    if (initialisedRef.current) {
      return;
    }
    initialisedRef.current = true;
    const start = parseStartParam();
    const target = mapTabToPath(start.tab);
    const stored = getUIPreferences().lastTab || "/";
    const desired = target ?? stored;
    if (desired !== location.pathname) {
      navigate(desired, { replace: true });
    }
  }, [location.pathname, navigate]);

  useEffect(() => {
    setLastTabPreference(location.pathname);
  }, [location.pathname]);

  const activeTab = useMemo(() => tabs.find((tab) => tab.path === location.pathname) ?? tabs[0], [location.pathname]);

  const handleToggleTheme = () => {
    const nextTheme: ThemeMode = theme === "dark" ? "light" : "dark";
    setThemePreference(nextTheme);
    onThemeChange(nextTheme);
  };

  return (
    <div className="app-shell">
      <TopBar title={activeTab.title} theme={theme} onToggleTheme={handleToggleTheme} />
      <main className="app-content">
        <AppRoutes
          settingsProps={{
            theme,
            onThemeChange: (nextTheme) => {
              setThemePreference(nextTheme);
              onThemeChange(nextTheme);
            },
            initDataUnsafe
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
      />
    </div>
  );
}
