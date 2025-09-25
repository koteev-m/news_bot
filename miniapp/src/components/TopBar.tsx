import { expand } from "../lib/telegram";
import type { ThemeMode } from "../lib/session";

interface TopBarProps {
  title: string;
  theme: ThemeMode;
  onToggleTheme: () => void;
}

export function TopBar({ title, theme, onToggleTheme }: TopBarProps): JSX.Element {
  const appName = import.meta.env.VITE_APP_NAME ?? "Mini App";
  return (
    <header className="top-bar">
      <div>
        <h1>{title || appName}</h1>
        <span className="top-bar__subtitle">{appName}</span>
      </div>
      <div className="top-bar__actions">
        <button type="button" onClick={() => expand()} className="top-bar__button">
          Expand
        </button>
        <button type="button" onClick={onToggleTheme} className="top-bar__button">
          {theme === "dark" ? "Light" : "Dark"}
        </button>
      </div>
    </header>
  );
}
