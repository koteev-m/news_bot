import { mockApi, expect, test, type MockApiHandlers } from "./fixtures";
import { mockAuthOk, mockImportByUrl, mockImportCsv } from "./mocks";

function createBaseHandlers(): MockApiHandlers {
  return {
    ...mockAuthOk(),
    "GET /api/reports/portfolio*": (request) => {
      expect(request.headers()["authorization"]).toMatch(/^Bearer\s+/);
      return {
        body: {
          summary: {
            totalValue: 15000,
            realizedPnl: 250,
            unrealizedPnl: 125
          },
          generatedAt: "2024-04-03T08:00:00Z"
        }
      };
    },
    "GET /api/quotes*": (request) => {
      expect(request.headers()["authorization"]).toMatch(/^Bearer\s+/);
      return {
        body: [
          { instrumentId: "AAPL", price: 172, changePct: 0.02, updatedAt: "2024-04-03T07:58:00Z" }
        ]
      };
    }
  };
}

test.describe("import", () => {
  const portfolioId = "123e4567-e89b-12d3-a456-426614174000";

  test("imports CSV file end-to-end", async ({ page }) => {
    const handlers = {
      ...createBaseHandlers(),
      ...mockImportCsv()
    };

    await mockApi(page, handlers);

    await page.goto("/");
    await page.getByRole("button", { name: "Import", exact: true }).click();

    await page.fill("#import-portfolio-id", portfolioId);

    const csvContent = [
      "datetime,ticker,exchange,side,quantity,price,currency",
      "2024-01-01T10:00:00Z,AAPL,NASDAQ,buy,10,150,USD"
    ].join("\n");

    await page.setInputFiles("input[type=\"file\"]", {
      name: "trades.csv",
      mimeType: "text/csv",
      buffer: Buffer.from(csvContent, "utf-8")
    });

    await expect(page.getByText("Preview:")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Map CSV columns" })).toBeVisible();

    await page.getByRole("button", { name: "Save mapping" }).click();
    await page.getByRole("button", { name: "Upload CSV" }).click();

    await expect(page.getByRole("heading", { name: "Import report" })).toBeVisible();
    await expect(page.getByText(/Inserted: 2/)).toBeVisible();
    await expect(page.getByText(/Skipped duplicates: 0/)).toBeVisible();
  });

  test("imports trades by URL", async ({ page }) => {
    const handlers = {
      ...createBaseHandlers(),
      ...mockImportByUrl()
    };

    await mockApi(page, handlers);

    await page.goto("/");
    await page.getByRole("button", { name: "Import", exact: true }).click();

    await page.fill("#import-portfolio-id", portfolioId);

    await page.getByRole("button", { name: "By URL" }).click();

    const csvUrl = "https://example.com/export.csv";
    await page.fill("#import-url", csvUrl);
    await page.getByRole("button", { name: "Import from URL" }).click();

    await expect(page.getByRole("heading", { name: "Import report" })).toBeVisible();
    await expect(page.getByText(/Inserted: 2/)).toBeVisible();
  });
});
