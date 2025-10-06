import { useEffect, useState, type ChangeEvent, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { useLocale } from "../hooks/useLocale";

type FeedbackFormState = {
  category: string;
  subject: string;
  message: string;
  locale: string;
  appVersion?: string;
  deviceInfo?: string;
};

type FeedbackResponse = {
  ticketId: number;
};

export function Feedback(): JSX.Element {
  const { t } = useTranslation();
  const { locale } = useLocale();
  const [form, setForm] = useState<FeedbackFormState>({
    category: "idea",
    subject: "",
    message: "",
    locale,
  });
  const [busy, setBusy] = useState(false);
  const [success, setSuccess] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setForm((prev) => ({ ...prev, locale }));
  }, [locale]);

  const handleChange = (field: keyof FeedbackFormState) => (
    event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>,
  ) => {
    const value = event.target.value;
    setForm((prev) => ({ ...prev, [field]: value }));
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setSuccess(null);
    try {
      const response = await fetch(`${import.meta.env.VITE_API_BASE}/api/support/feedback`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(form),
      });
      if (response.status === 429) {
        setError(t("feedback.rateLimited", "Too many requests, please try later / Слишком много запросов, повторите позже"));
        return;
      }
      if (!response.ok) {
        throw new Error(String(response.status));
      }
      const payload = (await response.json()) as FeedbackResponse;
      setSuccess(t("feedback.success", "Thank you! Ticket #{{id}}", { id: payload.ticketId }));
      setForm({ category: "idea", subject: "", message: "", locale });
    } catch (_error) {
      setError(t("error.generic", "Something went wrong / Что-то пошло не так"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <section className="card" aria-labelledby="feedback-heading">
        <h2 id="feedback-heading">{t("feedback.title", "Feedback / Обратная связь")}</h2>
        {success && (
          <p role="status" aria-live="polite" className="success-text">
            {success}
          </p>
        )}
        {error && (
          <p role="alert" className="error-text">
            {error}
          </p>
        )}
        <form onSubmit={handleSubmit} aria-describedby="feedback-hint">
          <p id="feedback-hint">{t("feedback.hint", "No personal data please / Не указывайте персональные данные")}</p>
          <label htmlFor="feedback-category">{t("feedback.category", "Category / Категория")}</label>
          <select
            id="feedback-category"
            value={form.category}
            onChange={handleChange("category")}
            disabled={busy}
            required
          >
            <option value="idea">{t("feedback.category.idea", "Idea / Идея")}</option>
            <option value="bug">{t("feedback.category.bug", "Bug / Ошибка")}</option>
            <option value="billing">{t("feedback.category.billing", "Billing / Оплата")}</option>
            <option value="import">{t("feedback.category.import", "Import / Импорт")}</option>
          </select>

          <label htmlFor="feedback-subject">{t("feedback.subject", "Subject / Тема")}</label>
          <input
            id="feedback-subject"
            type="text"
            value={form.subject}
            onChange={handleChange("subject")}
            maxLength={120}
            required
            disabled={busy}
          />

          <label htmlFor="feedback-message">{t("feedback.message", "Message / Сообщение")}</label>
          <textarea
            id="feedback-message"
            value={form.message}
            onChange={handleChange("message")}
            maxLength={4000}
            required
            disabled={busy}
            rows={6}
          />

          <button type="submit" disabled={busy} aria-busy={busy}>
            {t("feedback.submit", "Send / Отправить")}
          </button>
        </form>
      </section>
    </div>
  );
}
