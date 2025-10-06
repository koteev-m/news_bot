import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useLocale } from "../hooks/useLocale";

type FaqItem = {
  locale: string;
  slug: string;
  title: string;
  bodyMd: string;
  updatedAt: string;
};

function renderParagraphs(markdown: string): string[] {
  return markdown
    .split(/\n{2,}/)
    .map((chunk) => chunk.trim())
    .filter((chunk) => chunk.length > 0);
}

export function Help(): JSX.Element {
  const { t } = useTranslation();
  const { locale } = useLocale();
  const [items, setItems] = useState<FaqItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(false);
    fetch(`${import.meta.env.VITE_API_BASE}/api/support/faq/${locale}`, { signal: controller.signal })
      .then((response) => {
        if (!response.ok) {
          throw new Error(String(response.status));
        }
        return response.json() as Promise<FaqItem[]>;
      })
      .then((data) => {
        if (!controller.signal.aborted) {
          setItems(data);
        }
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setError(true);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setLoading(false);
        }
      });
    return () => {
      controller.abort();
    };
  }, [locale]);

  return (
    <div className="page">
      <section className="card" aria-labelledby="help-heading">
        <h2 id="help-heading">{t("help.title", "Help & Support / Поддержка")}</h2>
        {loading && (
          <p role="status" aria-live="polite">
            {t("loading", "Loading… / Загрузка…")}
          </p>
        )}
        {error && (
          <p role="alert" className="error-text">
            {t("error.generic", "Something went wrong / Что-то пошло не так")}
          </p>
        )}
        {!loading && !error && items.length === 0 && (
          <p role="status">{t("help.empty", "No FAQ entries yet / Пока нет статей")}</p>
        )}
        <ul className="faq-list">
          {items.map((item) => (
            <li key={item.slug} className="faq-list__item">
              <details>
                <summary>{item.title}</summary>
                <article aria-label={item.title}>
                  {renderParagraphs(item.bodyMd).map((paragraph, index) => (
                    <p key={index}>{paragraph}</p>
                  ))}
                  <p className="faq-updated">
                    <span>{t("help.updated", "Updated / Обновлено")}:</span>{" "}
                    <time dateTime={item.updatedAt}>{new Date(item.updatedAt).toLocaleString(locale)}</time>
                  </p>
                </article>
              </details>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
