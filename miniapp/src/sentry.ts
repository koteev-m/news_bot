import * as Sentry from "@sentry/browser";

export function initSentry() {
  const dsn = import.meta.env.VITE_SENTRY_DSN as string | undefined;
  if (!dsn) return;
  Sentry.init({
    dsn,
    environment: (import.meta.env.VITE_SENTRY_ENV as string | undefined) || "dev",
    release: import.meta.env.VITE_SENTRY_RELEASE as string | undefined,
    tracesSampleRate: 0.0
  });
  Sentry.setUser(null);
}
