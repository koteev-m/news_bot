import { mockApi, expect, test, type MockApiHandlers } from "./fixtures";
import { mockAuthOk, mockPositions } from "./mocks";

function createDashboardHandlers(): MockApiHandlers {
  return {
    "GET /api/reports/portfolio*": (request) => {
      expect(request.headers()["authorization"]).toMatch(/^Bearer\s+/);
      return {
        body: {
          summary: {
            totalValue: 12500,
            realizedPnl: 320,
            unrealizedPnl: 180
          },
          generatedAt: "2024-04-01T10:00:00Z"
        }
      };
    },
    "GET /api/quotes*": (request) => {
      expect(request.headers()["authorization"]).toMatch(/^Bearer\s+/);
      return {
        body: [
          { instrumentId: "AAPL", price: 165, changePct: 0.012, updatedAt: "2024-04-01T09:50:00Z" },
          { instrumentId: "TSLA", price: 220, changePct: -0.024, updatedAt: "2024-04-01T09:48:00Z" }
        ]
      };
    }
  };
}

test.describe("auth", () => {
  test("authenticates with Telegram init data and keeps JWT in memory", async ({ page }) => {
    const handlers = {
      ...mockAuthOk(),
      ...mockPositions(),
      ...createDashboardHandlers()
    };

    await mockApi(page, handlers);

    await page.goto("/");

    await expect(page.getByRole("heading", { name: "Portfolio overview" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Dashboard" })).toBeVisible();

    const storedValues = await page.evaluate(() => {
      const result: Record<string, string> = {};
      for (let index = 0; index < window.localStorage.length; index += 1) {
        const key = window.localStorage.key(index);
        if (key) {
          const value = window.localStorage.getItem(key);
          if (value) {
            result[key] = value;
          }
        }
      }
      return result;
    });

    expect(Object.values(storedValues).some((value) => value.includes("TEST_JWT"))).toBeFalsy();
  });

  test("re-authenticates on 401 responses", async ({ page }) => {
    let authCalls = 0;
    const handlers = {
      ...mockAuthOk({
        tokens: ["TEST_JWT", "TEST_JWT_REFRESHED"],
        onRequest: (_, __, call) => {
          authCalls = call;
        }
      }),
      ...mockPositions({
        expectedToken: (call) => (call === 1 ? "TEST_JWT" : "TEST_JWT_REFRESHED"),
        onCall: (_request, call) => {
          if (call === 1) {
            return { status: 401, body: { message: "Unauthorized" } };
          }
          return undefined;
        }
      }),
      ...createDashboardHandlers()
    };

    await mockApi(page, handlers);

    await page.goto("/");
    await page.getByRole("button", { name: "Positions", exact: true }).click();

    await expect(page.getByText("Unauthorized. Please restart the Mini App.")).toBeVisible();

    await page.reload();
    await expect.poll(() => authCalls).toBeGreaterThanOrEqual(2);
    await expect(page.getByText("Authorizing session...")).toBeHidden();

    await page.getByRole("button", { name: "Positions", exact: true }).click();

    await expect(page.getByRole("cell", { name: "AAPL" })).toBeVisible();
  });
});
