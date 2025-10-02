import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { act, cleanup, render, screen } from "@testing-library/react";
import { createElement } from "react";
import { I18nextProvider } from "react-i18next";
import { detectLocale, getInitialLocale, i18n } from "../i18n";
import { useLocale } from "../hooks/useLocale";

function LocaleProbe({ onReady }: { onReady: (locale: ReturnType<typeof useLocale>) => void }) {
  const locale = useLocale();
  onReady(locale);
  return createElement("span", { "data-testid": "locale-value" }, locale.locale);
}

describe("i18n", () => {
  let originalNavigatorLanguage: PropertyDescriptor | undefined;

  beforeEach(() => {
    vi.stubEnv("NODE_ENV", "test");
    window.localStorage.clear();
    Reflect.deleteProperty(globalThis, "Telegram");
    originalNavigatorLanguage = Object.getOwnPropertyDescriptor(window.navigator, "language");
    void i18n.changeLanguage("en");
  });

  afterEach(() => {
    cleanup();
    Reflect.deleteProperty(globalThis, "Telegram");
    if (originalNavigatorLanguage) {
      Object.defineProperty(window.navigator, "language", originalNavigatorLanguage);
    }
  });

  test("detects locale from Telegram WebApp", () => {
    const telegram = {
      WebApp: {
        initDataUnsafe: {
          user: {
            language_code: "ru",
          },
        },
      },
    };
    Object.defineProperty(globalThis, "Telegram", {
      value: telegram,
      configurable: true,
    });
    expect(detectLocale()).toBe("ru");
  });

  test("falls back to navigator language when Telegram is missing", () => {
    Object.defineProperty(window.navigator, "language", {
      value: "ru-RU",
      configurable: true,
    });
    expect(detectLocale()).toBe("ru");
  });

  test("persists locale changes and updates html lang attribute", () => {
    const onReady = vi.fn();
    render(createElement(I18nextProvider, { i18n }, createElement(LocaleProbe, { onReady })));
    const localeElement = screen.getByTestId("locale-value");
    expect(localeElement.textContent).toBe(getInitialLocale());
    const latestCall = onReady.mock.calls.at(-1);
    expect(latestCall).toBeDefined();
    const localeState = latestCall![0];
    act(() => {
      localeState.setLocale("ru");
    });
    expect(document.documentElement.lang).toBe("ru");
    expect(window.localStorage.getItem("locale")).toBe("ru");
    expect(localeElement.textContent).toBe("ru");
  });
});
