import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Loading } from "../components/Loading";
import { useToaster } from "../components/Toaster";
import { getQuotes } from "../lib/api";
import type { Quote } from "../lib/api";
import { formatDate } from "../lib/format";

export function Calendar(): JSX.Element {
  const { t } = useTranslation();
  const toaster = useToaster();
  const [events, setEvents] = useState<Quote[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    getQuotes()
      .then((data) => {
        if (!cancelled) {
          setEvents(data);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          toaster.notifyError((err as Error).message || t("error.generic"));
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [t, toaster]);

  return (
    <div className="page">
      <section className="card">
        <h2>{t("calendar.title")}</h2>
        {loading ? (
          <Loading variant="skeleton" labelKey="loading" />
        ) : events.length === 0 ? (
          <p>{t("calendar.noEvents")}</p>
        ) : (
          <ul className="calendar-list">
            {events.map((event) => (
              <li key={event.instrumentId}>
                <div className="calendar-list__instrument">{event.instrumentId}</div>
                <div className="calendar-list__date">{formatDate(event.updatedAt)}</div>
                <div className="calendar-list__note">{t("calendar.eventNote")}</div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
