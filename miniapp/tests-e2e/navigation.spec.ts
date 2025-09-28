import { mockApi, expect, test, type MockApiHandlers } from "./fixtures";
import { mockAuthOk, mockPositions, mockTrades } from "./mocks";

function createSharedHandlers(): MockApiHandlers {
  return {
    ...mockAuthOk(),
    ...mockPositions(),
    ...mockTrades(),
    "GET /api/reports/portfolio*": (request) => {
      expect(request.headers()["authorization"]).toMatch(/^Bearer\s+/);
      return {
        body: {
          summary: {
            totalValue: 14250,
            realizedPnl: 210,
            unrealizedPnl: 95
          },
          generatedAt: "2024-04-02T11:00:00Z"
        }
      };
    },
    "GET /api/quotes*": (request) => {
      expect(request.headers()["authorization"]).toMatch(/^Bearer\s+/);
      return {
        body: [
          { instrumentId: "AAPL", price: 170, changePct: 0.01, updatedAt: "2024-04-02T10:55:00Z" },
          { instrumentId: "TSLA", price: 215, changePct: -0.02, updatedAt: "2024-04-02T10:54:00Z" }
        ]
      };
    }
  };
}

test.describe("navigation", () => {
  test("tab bar routes between primary views", async ({ page }) => {
    await page.addInitScript(() => {
      const global = window as typeof window & {
        Telegram?: { WebApp?: { initData?: string; initDataUnsafe?: Record<string, unknown> } };
      };
      const namespace = (global.Telegram = global.Telegram || {});
      if (!namespace.WebApp) {
        namespace.WebApp = {
          initData:
            "user=%7B%22id%22%3A7446417641%2C%22username%22%3A%22test%22%7D&auth_date=1700000000&hash=stub",
          initDataUnsafe: { user: { id: 7446417641, username: "test" }, start_param: "" },
          ready: () => {},
          expand: () => {},
          themeParams: {},
          MainButton: { show: () => {}, hide: () => {}, text: "" },
          BackButton: { show: () => {}, hide: () => {} }
        };
      }
    });

    await mockApi(page, createSharedHandlers());

    await page.goto("/");

    await expect(page).toHaveURL(/\/?$/);
    await page.locator("nav.tab-bar").waitFor({ state: "visible", timeout: 7000 });
    await expect(page.getByRole("heading", { level: 2, name: "Portfolio overview" })).toBeVisible({ timeout: 7000 });

    await page.getByRole("button", { name: "Positions", exact: true }).click();
    await expect(page).toHaveURL(/\/positions$/);
    await expect(page.getByRole("heading", { level: 2, name: "Positions" })).toBeVisible();
    await expect(page.getByRole("cell", { name: "AAPL" })).toBeVisible();

    await page.getByRole("button", { name: "Trades", exact: true }).click();
    await expect(page).toHaveURL(/\/trades$/);
    await expect(page.getByRole("heading", { level: 2, name: "Trades" })).toBeVisible();
    await expect(page.getByRole("cell", { name: "buy" })).toBeVisible();

    await page.getByRole("button", { name: "Import", exact: true }).click();
    await expect(page).toHaveURL(/\/import$/);
    await expect(page.getByRole("heading", { level: 2, name: "Import trades" })).toBeVisible();

    await page.getByRole("button", { name: "Reports", exact: true }).click();
    await expect(page).toHaveURL(/\/reports$/);
    await expect(page.getByRole("heading", { level: 2, name: "Reports" })).toBeVisible();

    await page.getByRole("button", { name: "Settings", exact: true }).click();
    await expect(page).toHaveURL(/\/settings$/);
    await expect(page.getByRole("heading", { level: 2, name: "Settings" })).toBeVisible();
  });
});
