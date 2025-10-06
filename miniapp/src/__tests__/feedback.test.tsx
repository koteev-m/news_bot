import { describe, expect, beforeAll, beforeEach, afterEach, it, vi } from "vitest";
import "@testing-library/jest-dom/vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { I18nextProvider } from "react-i18next";
import { Feedback } from "../pages/Feedback";
import { i18n } from "../i18n";

const apiBase = "https://api.test";

type FetchMock = ReturnType<typeof vi.fn>;

describe("Feedback page", () => {
  let fetchMock: FetchMock;
  const originalFetch: typeof fetch | undefined = global.fetch;

  beforeAll(() => {
    Object.assign(import.meta.env, { VITE_API_BASE: apiBase });
  });

  beforeEach(async () => {
    fetchMock = vi.fn();
    global.fetch = fetchMock as unknown as typeof fetch;
    await i18n.changeLanguage("en");
  });

  afterEach(() => {
    global.fetch = originalFetch;
    fetchMock.mockReset();
  });

  it("submits feedback successfully", async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ ticketId: 42 }), {
        status: 202,
        headers: { "content-type": "application/json" },
      }),
    );

    render(
      <I18nextProvider i18n={i18n}>
        <Feedback />
      </I18nextProvider>,
    );

    const user = userEvent.setup();
    const subject = screen.getByLabelText(/subject/i);
    const message = screen.getByLabelText(/message/i);
    await user.type(subject, "Hello");
    await user.type(message, "Great app!");
    await user.click(screen.getByRole("button", { name: /send/i }));

    const status = await screen.findByRole("status");
    expect(status.textContent).toMatch(/ticket #42/i);

    await waitFor(() => {
      expect(subject).toHaveValue("");
      expect(message).toHaveValue("");
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(`${apiBase}/api/support/feedback`);
    expect(init?.method).toBe("POST");
    const payload = JSON.parse(String(init?.body ?? "{}"));
    expect(payload).toMatchObject({ category: "idea", locale: "en" });
  });

  it("shows rate-limit error", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 429 }));

    render(
      <I18nextProvider i18n={i18n}>
        <Feedback />
      </I18nextProvider>,
    );

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/subject/i), "Hi");
    await user.type(screen.getByLabelText(/message/i), "Body");
    await user.click(screen.getByRole("button", { name: /send/i }));

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toMatch(/too many requests/i);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
