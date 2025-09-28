import { mockApi, expect, test, setStartParam, type MockApiHandlers } from "./fixtures";
import { mockAuthOk } from "./mocks";

function createHandlers(): MockApiHandlers {
  return {
    ...mockAuthOk(),
    "GET /api/reports/portfolio*": (request) => {
      expect(request.headers()["authorization"]).toMatch(/^Bearer\s+/);
      return {
        body: {
          summary: {
            totalValue: 16000,
            realizedPnl: 400,
            unrealizedPnl: 210
          },
          generatedAt: "2024-04-04T12:00:00Z"
        }
      };
    },
    "GET /api/quotes*": (request) => {
      expect(request.headers()["authorization"]).toMatch(/^Bearer\s+/);
      return {
        body: [
          { instrumentId: "AAPL", price: 174, changePct: 0.015, updatedAt: "2024-04-04T11:55:00Z" }
        ]
      };
    }
  };
}

test.describe("start_param", () => {
  test("opens Import tab when start_param specifies tab=import", async ({ page }) => {
    await setStartParam(page, "tab=import");
    await mockApi(page, createHandlers());

    await page.goto("/");

    await page.waitForURL(/\/import$/);
    await expect(page.getByRole("heading", { name: "Import trades" })).toBeVisible();
  });
});
