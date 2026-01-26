import { afterEach, beforeAll, describe, expect, test, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { createElement, type ReactElement } from "react";
import { MemoryRouter } from "react-router-dom";
import { I18nextProvider } from "react-i18next";
import { axe } from "vitest-axe";
import * as matchers from "vitest-axe/matchers";
import 'vitest-axe/extend-expect';

// jsdom does not implement canvas; axe tries to call getContext for icon detection
HTMLCanvasElement.prototype.getContext = vi.fn(() => null);

import { Dashboard } from "../pages/Dashboard";
import { Import } from "../pages/Import";
import { Settings } from "../pages/Settings";
import { ToasterProvider } from "../components/Toaster";
import { i18n } from "../i18n";
import type { InitDataUnsafe } from "../lib/telegram";
import { setPortfolioIdPreference } from "../lib/session";

expect.extend(matchers);

vi.mock("../lib/api", () => ({
  getReport: vi.fn(async () => ({
    generatedAt: new Date().toISOString(),
    summary: {
      totalValue: 1000,
      realizedPnl: 120,
      unrealizedPnl: 80,
    },
  })),
  getQuotes: vi.fn(async () => [
    { instrumentId: "AAPL", price: 150, changePct: 0.05, updatedAt: new Date().toISOString() },
  ]),
  getPositions: vi.fn(async () => []),
  getTrades: vi.fn(async () => ({
    items: [],
    total: 0,
    limit: 20,
    offset: 0,
  })),
  postImportCsv: vi.fn(),
  postImportByUrl: vi.fn(),
}));

vi.mock("../lib/session", async () => {
  const actual = await vi.importActual<typeof import("../lib/session")>("../lib/session");
  return {
    ...actual,
    setThemePreference: vi.fn(),
  };
});

function renderWithProviders(element: ReactElement) {
  return render(
    createElement(
      I18nextProvider,
      { i18n },
      createElement(ToasterProvider, undefined, createElement(MemoryRouter, undefined, element)),
    ),
  );
}

describe("accessibility", () => {
  beforeAll(() => {
    void i18n.changeLanguage("en");
    setPortfolioIdPreference("00000000-0000-0000-0000-000000000001");
  });

  afterEach(() => {
    cleanup();
  });

  test("dashboard page has no accessibility violations", async () => {
    const { container } = renderWithProviders(createElement(Dashboard));
    await screen.findByText(/Total value/i);
    const results = await axe(container);
    // @ts-expect-error vitest-axe matcher typing provided via setup file
    expect(results).toHaveNoViolations();
  });

  test("import page has no accessibility violations", async () => {
    const { container } = renderWithProviders(createElement(Import));
    await screen.findByLabelText(/Portfolio ID/i);
    const results = await axe(container);
    // @ts-expect-error vitest-axe matcher typing provided via setup file
    expect(results).toHaveNoViolations();
  });

  test("settings page has no accessibility violations", async () => {
    const initDataUnsafe: InitDataUnsafe = {
      user: {
        id: 1,
        language_code: "en",
        first_name: "Test",
      },
    } as InitDataUnsafe;
    const { container } = renderWithProviders(
      createElement(Settings, { theme: "light", onThemeChange: () => undefined, initDataUnsafe }),
    );
    await screen.findByText(/Security note/i);
    const results = await axe(container);
    // @ts-expect-error vitest-axe matcher typing provided via setup file
    expect(results).toHaveNoViolations();
  });
});
