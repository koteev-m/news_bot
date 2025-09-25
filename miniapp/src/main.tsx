import { StrictMode, useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { App } from "./App";
import "./styles.css";
import { getWebApp, ready, expand, getInitData, getInitDataUnsafe } from "./lib/telegram";
import type { InitDataUnsafe } from "./lib/telegram";
import { AuthRequired, useAuthGuard } from "./lib/guards";
import { loadUIPreferences } from "./lib/session";
import type { ThemeMode } from "./lib/session";

function Root(): JSX.Element {
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

  const authState = useAuthGuard(initData, initReady);

  const content = useMemo(() => {
    if (authState.status === "pending") {
      return <div className="loading-screen">Authorizing session...</div>;
    }
    if (authState.status === "unauthorized") {
      return <AuthRequired error={authState.error} />;
    }
    return (
      <BrowserRouter>
        <App theme={theme} onThemeChange={setTheme} initDataUnsafe={initDataUnsafe} />
      </BrowserRouter>
    );
  }, [authState, theme, initDataUnsafe]);

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
  </StrictMode>
);
