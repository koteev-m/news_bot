import { useTranslation } from "react-i18next";

interface PortfolioIdFieldProps {
  id: string;
  value: string;
  onChange: (value: string) => void;
  error?: string | null;
}

export function PortfolioIdField({ id, value, onChange, error }: PortfolioIdFieldProps): JSX.Element {
  const { t } = useTranslation();
  const helpId = `${id}-help`;

  return (
    <div className="portfolio-id-field">
      <label htmlFor={id}>{t("import.portfolioId")}</label>
      <input
        id={id}
        type="text"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={t("import.portfolioPlaceholder")}
        aria-describedby={helpId}
        aria-invalid={Boolean(error)}
      />
      <p id={helpId} className="import-page__hint">
        {t("import.portfolioHelp")}
      </p>
      {error ? (
        <span className="import-page__error" role="alert">
          {error}
        </span>
      ) : null}
    </div>
  );
}
