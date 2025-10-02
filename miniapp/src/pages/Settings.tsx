import { useTranslation } from "react-i18next";
import { LanguageSelect } from "../components/LanguageSelect";
import type { InitDataUnsafe } from "../lib/telegram";
import type { ThemeMode } from "../lib/session";
import { clearJwt, clearUIPreferences, setThemePreference } from "../lib/session";

interface SettingsProps {
  theme: ThemeMode;
  onThemeChange: (theme: ThemeMode) => void;
  initDataUnsafe: InitDataUnsafe | null;
}

export function Settings({ theme, onThemeChange, initDataUnsafe }: SettingsProps): JSX.Element {
  const { t } = useTranslation();
  const username = initDataUnsafe?.user?.username ?? "-";

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
        <h2>{t("settings.title")}</h2>
        <div className="settings-item">
          <label htmlFor="theme-select">{t("settings.theme")}</label>
          <select
            id="theme-select"
            value={theme}
            onChange={(event) => handleThemeChange(event.target.value as ThemeMode)}
            aria-describedby="theme-hint"
          >
            <option value="light">{t("settings.theme.light")}</option>
            <option value="dark">{t("settings.theme.dark")}</option>
          </select>
          <p id="theme-hint" className="settings-item__hint">
            {t("settings.themeHint")}
          </p>
        </div>
        <div className="settings-item">
          <label htmlFor="language-select">{t("settings.language")}</label>
          <LanguageSelect id="language-select" />
        </div>
        <div className="settings-item">
          <button type="button" onClick={handleClearSession}>
            {t("settings.clearSession")}
          </button>
        </div>
        <div className="settings-item" aria-live="polite">
          <span>{t("settings.telegramUser")}</span>
          <strong>{username}</strong>
        </div>
        <p role="note">{t("settings.jwtNote")}</p>
      </section>
    </div>
  );
}
