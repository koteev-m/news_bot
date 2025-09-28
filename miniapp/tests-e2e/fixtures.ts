import { createRequire } from "module";
import { test as base, expect, type Page, type Request } from "@playwright/test";

const require = createRequire(import.meta.url);
const telegramStubPath = require.resolve("./helpers/telegram-stub.js");

export const test = base;
export { expect };

export interface MockApiResponse {
  status?: number;
  body?: unknown;
  headers?: Record<string, string>;
}

export type MockApiHandler = MockApiResponse | ((request: Request) => Promise<MockApiResponse> | MockApiResponse);

export type MockApiHandlers = Record<string, MockApiHandler>;

function patternToRegex(pattern: string): RegExp {
  const escaped = pattern.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const withWildcards = escaped.replace(/\\\*/g, ".*");
  return new RegExp(`^${withWildcards}$`);
}

interface ParsedPattern {
  method?: string;
  regex: RegExp;
}

function parsePattern(pattern: string): ParsedPattern {
  const trimmed = pattern.trim();
  const parts = trimmed.split(/\s+/);
  if (parts.length > 1 && /^[A-Z]+$/.test(parts[0])) {
    const [method, ...rest] = parts;
    return { method, regex: patternToRegex(rest.join(" ")) };
  }
  return { regex: patternToRegex(trimmed) };
}

function normalizeUrl(url: string): string {
  const parsed = new URL(url);
  return `${parsed.pathname}${parsed.search}`;
}

export async function mockApi(page: Page, handlers: MockApiHandlers): Promise<void> {
  await page.route("**/api/**", async (route, request) => {
    const target = normalizeUrl(request.url());
    const method = request.method();
    const entry = Object.entries(handlers).find(([pattern]) => {
      const { method: expectedMethod, regex } = parsePattern(pattern);
      if (expectedMethod && expectedMethod.toUpperCase() !== method.toUpperCase()) {
        return false;
      }
      return regex.test(target);
    });
    if (!entry) {
      await route.fulfill({
        status: 500,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: `Unhandled API request: ${method} ${target}` })
      });
      return;
    }
    const [, handler] = entry;
    const response = typeof handler === "function" ? await handler(request) : handler;
    const status = response.status ?? 200;
    const headers = {
      ...(response.body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(response.headers ?? {})
    };
    const bodyValue = response.body;
    await route.fulfill({
      status,
      headers,
      body: bodyValue === undefined ? undefined : typeof bodyValue === "string" ? bodyValue : JSON.stringify(bodyValue)
    });
  });
}

export async function setStartParam(page: Page, value: string): Promise<void> {
  await page.addInitScript((startParam) => {
    const global = window as typeof window & {
      Telegram?: { WebApp?: { initData?: string; initDataUnsafe?: Record<string, unknown> } };
    };
    const namespace = (global.Telegram = global.Telegram || {});
    namespace.WebApp = namespace.WebApp || { initData: "", initDataUnsafe: {} };
    const webApp = namespace.WebApp;
    const params = new URLSearchParams(webApp.initData || "");
    if (startParam) {
      params.set("start_param", startParam);
    } else {
      params.delete("start_param");
    }
    webApp.initData = params.toString();
    webApp.initDataUnsafe = {
      ...(webApp.initDataUnsafe || {}),
      start_param: startParam
    };
  }, value);
}

test.beforeEach(async ({ context }) => {
  await context.addInitScript({ path: telegramStubPath });
});
