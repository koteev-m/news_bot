# Portfolio Mini App

This directory contains a Telegram Mini App built with Vite, React, and TypeScript. The project integrates with a backend service that verifies Telegram Web App sessions and issues a JWT token for authenticated requests.

## Prerequisites

- pnpm (recommended) or npm/yarn
- Node.js 18+

## Getting started

```bash
pnpm install
pnpm dev
```

The development server runs on [http://localhost:5173](http://localhost:5173).

To build and preview the production bundle:

```bash
pnpm build
pnpm preview
```

Run type checking and tests:

```bash
pnpm typecheck
pnpm test
```

## P20 — e2e

End-to-end coverage for the Mini App is implemented with Playwright and can be executed locally or in CI. The suite launches the
production preview via `vite preview` and relies entirely on mocked network interactions, so no external backend is required.

### Install Playwright browsers

```bash
pnpm playwright:install
```

### Run the tests

```bash
pnpm test:e2e
```

### Explore with the Playwright UI

```bash
pnpm test:e2e:ui
```

During each run the Telegram Mini Apps JavaScript API is stubbed through an init script so that the app can boot with synthetic
`initData`. API calls to `/api/*` endpoints are intercepted through Playwright routing helpers that return deterministic JSON
responses and assert the `Authorization` headers carry the in-memory JWT. The Playwright `webServer` configuration starts the
application using `pnpm preview` on port 5173, mirroring the production build pipeline.

## Telegram auth flow

When opened inside Telegram, the Mini App reads `initData` supplied by the client. The frontend sends this data to the backend endpoint `/api/auth/telegram/verify` which performs validation and responds with a JWT. The token is stored only in memory for the current session. UI preferences such as the theme and last opened tab are persisted to `localStorage`.

Launching the app directly in a browser without Telegram will display instructions describing how to open the Mini App from Telegram.

## Deep link

[Open Mini App](https://t.me/your_bot_username?startapp=tab%3Ddashboard)

Replace `your_bot_username` with the actual bot name and adjust the `startapp` payload as needed.

## P37 — UX/i18n/a11y

This iteration adds a localized and accessible UI layer for the Telegram Mini App. Key improvements include:

- Dual-language support (English and Russian) with automatic detection from Telegram data or the browser.
- Loading skeletons and spinners for key pages, consistent error handling with a toast system, and a resilient error boundary.
- Keyboard-friendly navigation with a skip link, focus-visible styles, and tablist behavior on the bottom navigation.
- Reduced-motion support and live regions for loading or error states to keep assistive technologies in sync.

### Switching languages

The Settings page now exposes a language selector. Changes persist to `localStorage` and immediately update the interface as well as the `<html lang>` attribute.

### Accessibility checks

Run the dedicated a11y checks with:

```bash
pnpm test:a11y
```

Regular unit tests continue to run through `pnpm test`.
