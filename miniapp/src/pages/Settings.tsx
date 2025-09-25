import type { InitDataUnsafe } from "../lib/telegram";
import type { ThemeMode } from "../lib/session";
import { clearJwt, clearUIPreferences, setThemePreference } from "../lib/session";

interface SettingsProps {
  theme: ThemeMode;
  onThemeChange: (theme: ThemeMode) => void;
  initDataUnsafe: InitDataUnsafe | null;
}

export function Settings({ theme, onThemeChange, initDataUnsafe }: SettingsProps): JSX.Element {
  const username = initDataUnsafe?.user?.username ?? "Unknown";

  const handleThemeChange = (nextTheme: ThemeMode) => {
    setThemePreference(nextTheme);
    onThemeChange(nextTheme);
  };

  const handleClearSession = () => {
    clearJwt();
    clearUIPreferences();
    window.location.reload();
  };

  return (
    <div className="page">
      <section className="card">
        <h2>Settings</h2>
        <div className="settings-item">
          <label htmlFor="theme-select">Theme</label>
          <select
            id="theme-select"
            value={theme}
            onChange={(event) => handleThemeChange(event.target.value as ThemeMode)}
          >
            <option value="light">Light</option>
            <option value="dark">Dark</option>
          </select>
        </div>
        <div className="settings-item">
          <button type="button" onClick={handleClearSession}>
            Clear session
          </button>
        </div>
        <div className="settings-item">
          <span>Telegram user</span>
          <strong>{username}</strong>
        </div>
      </section>
    </div>
  );
}
