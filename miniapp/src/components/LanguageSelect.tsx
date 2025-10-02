import { ChangeEvent } from "react";
import { useTranslation } from "react-i18next";
import { useLocale } from "../hooks/useLocale";
import type { Locale } from "../i18n";

interface LanguageSelectProps {
  id?: string;
  className?: string;
}

export function LanguageSelect({ id, className }: LanguageSelectProps): JSX.Element {
  const { locale, setLocale } = useLocale();
  const { t } = useTranslation();

  const handleChange = (event: ChangeEvent<HTMLSelectElement>) => {
    const nextLocale = event.target.value as Locale;
    setLocale(nextLocale);
  };

  return (
    <select
      id={id}
      className={className}
      aria-label={t("settings.language")}
      value={locale}
      onChange={handleChange}
    >
      <option value="en">{t("language.en")}</option>
      <option value="ru">{t("language.ru")}</option>
    </select>
  );
}
