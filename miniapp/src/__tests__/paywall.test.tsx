import { describe, it, beforeAll, beforeEach, afterEach, expect, vi } from "vitest";
import "@testing-library/jest-dom/vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import React from "react";
import { I18nextProvider } from "react-i18next";
import { i18n } from "../i18n";
import Paywall from "../pages/Paywall";

const apiBase = "https://api.example";

type FetchMock = ReturnType<typeof vi.fn>;

describe("Paywall page", () => {
  let originalFetch: typeof fetch | undefined;
  let fetchMock: FetchMock;

  beforeAll(() => {
    Object.assign(import.meta.env, { VITE_API_BASE: apiBase });
  });

  beforeEach(async () => {
    originalFetch = global.fetch;
    fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input.toString();
      if (url === `${apiBase}/api/pricing/offers`) {
        return new Response(
          JSON.stringify({
            copyVariant: "A",
            priceVariant: "B",
            headingEn: "Heading",
            subEn: "Sub",
            ctaEn: "CTA",
            offers: [{ tier: "PRO", priceXtr: 900 }],
          }),
          {
            status: 200,
            headers: { "content-type": "application/json" },
          },
        );
      }
      if (url === `${apiBase}/api/pricing/cta`) {
        return new Response(null, { status: 202 });
      }
      return new Response(null, { status: 404 });
    }) as unknown as FetchMock;
    global.fetch = fetchMock as unknown as typeof fetch;
    await i18n.changeLanguage("en");
  });

  afterEach(() => {
    global.fetch = originalFetch as typeof fetch;
    fetchMock.mockReset();
  });

  it("renders offers and sends CTA click", async () => {
    render(
      <I18nextProvider i18n={i18n}>
        <Paywall />
      </I18nextProvider>,
    );

    await screen.findByText("900 Stars / month");
    const button = await screen.findByRole("button", { name: /upgrade now/i });
    fireEvent.click(button);

    await waitFor(() => {
      const calls = fetchMock.mock.calls.filter(([url]) => String(url).endsWith("/api/pricing/cta"));
      expect(calls.length).toBe(1);
      const [, init] = calls[0] as [string, RequestInit];
      expect(init?.method).toBe("POST");
      const body = JSON.parse(String(init?.body ?? "{}"));
      expect(body).toMatchObject({ plan: "PRO", variant: "B" });
    });
  });
});
