import { useEffect, useState } from "react";
import { getQuotes } from "../lib/api";
import type { Quote } from "../lib/api";
import { formatDate } from "../lib/format";

export function Calendar(): JSX.Element {
  const [events, setEvents] = useState<Quote[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    getQuotes()
      .then((data) => {
        if (!cancelled) {
          setEvents(data);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setError((err as Error).message);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className="page">
      <section className="card">
        <h2>Income calendar</h2>
        {error && <div className="error">{error}</div>}
        {events.length === 0 ? (
          <p>No upcoming events.</p>
        ) : (
          <ul className="calendar-list">
            {events.map((event) => (
              <li key={event.instrumentId}>
                <div className="calendar-list__instrument">{event.instrumentId}</div>
                <div className="calendar-list__date">{formatDate(event.updatedAt)}</div>
                <div className="calendar-list__note">Next payout estimate</div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
