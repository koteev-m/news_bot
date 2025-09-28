import type { Request } from "@playwright/test";
import { expect } from "./fixtures";
import type { MockApiHandlers, MockApiResponse } from "./fixtures";

interface Position {
  instrumentId: string;
  qty: number;
  avgPrice: number;
  lastPrice: number;
  upl: number;
}

interface Trade {
  id: string;
  instrumentId: string;
  qty: number;
  price: number;
  side: "buy" | "sell";
  executedAt: string;
}

interface TradesResponse {
  total: number;
  limit: number;
  offset: number;
  items: Trade[];
}

interface ImportReport {
  inserted: number;
  skippedDuplicates: number;
  failed: Array<{ line: number; error: string }>;
}

export interface MockAuthOptions {
  tokens?: string[];
  onRequest?: (request: Request, issuedToken: string, call: number) => void;
}

export function mockAuthOk(options: MockAuthOptions = {}): MockApiHandlers {
  const tokens = options.tokens && options.tokens.length > 0 ? options.tokens : ["TEST_JWT"];
  let call = 0;
  return {
    "POST /api/auth/telegram/verify": (request) => {
      const index = Math.min(call, tokens.length - 1);
      const token = tokens[index];
      call += 1;
      options.onRequest?.(request, token, call);
      return {
        body: {
          token,
          expiresAt: "2099-01-01T00:00:00Z",
          user: { id: 7446417641 }
        }
      };
    }
  };
}

interface PositionsMockOptions {
  positions?: Position[];
  expectedToken?: string | ((call: number) => string | null);
  onCall?: (request: Request, call: number) => MockApiResponse | void;
}

const defaultPositions: Position[] = [
  { instrumentId: "AAPL", qty: 10, avgPrice: 150, lastPrice: 155, upl: 50 },
  { instrumentId: "TSLA", qty: 4, avgPrice: 210, lastPrice: 215, upl: 20 }
];

export function mockPositions(options: PositionsMockOptions = {}): MockApiHandlers {
  let call = 0;
  return {
    "GET /api/positions": (request) => {
      call += 1;
      const expected = options.expectedToken
        ? typeof options.expectedToken === "function"
          ? options.expectedToken(call)
          : options.expectedToken
        : undefined;
      const header = request.headers()["authorization"];
      if (expected === null) {
        expect(header, "Authorization header should be absent").toBeFalsy();
      } else if (expected) {
        expect(header, "Authorization header must match expected token").toBe(`Bearer ${expected}`);
      } else {
        expect(header, "Authorization header must be set").toMatch(/^Bearer\s+/);
      }
      const override = options.onCall?.(request, call);
      if (override) {
        return override;
      }
      return { body: options.positions ?? defaultPositions };
    }
  };
}

interface TradesMockOptions {
  response?: TradesResponse;
  expectedToken?: string;
}

const defaultTrades: TradesResponse = {
  total: 1,
  limit: 20,
  offset: 0,
  items: [
    {
      id: "trade-1",
      instrumentId: "AAPL",
      qty: 10,
      price: 152,
      side: "buy",
      executedAt: "2024-01-01T10:00:00Z"
    }
  ]
};

export function mockTrades(options: TradesMockOptions = {}): MockApiHandlers {
  return {
    "GET /api/trades*": (request) => {
      const header = request.headers()["authorization"];
      if (options.expectedToken) {
        expect(header, "Authorization header must match expected token").toBe(`Bearer ${options.expectedToken}`);
      } else {
        expect(header, "Authorization header must be set").toMatch(/^Bearer\s+/);
      }
      return { body: options.response ?? defaultTrades };
    }
  };
}

interface ImportMockOptions {
  expectedToken?: string;
  report?: ImportReport;
}

const defaultImportReport: ImportReport = {
  inserted: 2,
  skippedDuplicates: 0,
  failed: []
};

export function mockImportCsv(options: ImportMockOptions = {}): MockApiHandlers {
  return {
    "POST /api/portfolio/*/trades/import/csv": (request) => {
      const header = request.headers()["authorization"];
      if (options.expectedToken) {
        expect(header, "Authorization header must match expected token").toBe(`Bearer ${options.expectedToken}`);
      } else {
        expect(header, "Authorization header must be set").toMatch(/^Bearer\s+/);
      }
      const body = request.postDataBuffer();
      expect(body, "CSV import must send multipart payload").toBeTruthy();
      const contentType = request.headers()["content-type"];
      expect(contentType, "CSV import must use multipart/form-data").toContain("multipart/form-data");
      return { body: options.report ?? defaultImportReport };
    }
  };
}

export function mockImportByUrl(options: ImportMockOptions = {}): MockApiHandlers {
  return {
    "POST /api/portfolio/*/trades/import/by-url": (request) => {
      const header = request.headers()["authorization"];
      if (options.expectedToken) {
        expect(header, "Authorization header must match expected token").toBe(`Bearer ${options.expectedToken}`);
      } else {
        expect(header, "Authorization header must be set").toMatch(/^Bearer\s+/);
      }
      const payload = request.postData();
      expect(payload, "Import by URL payload must be JSON").toBeTruthy();
      if (payload) {
        const parsed = JSON.parse(payload) as { url?: string };
        expect(parsed.url, "Import URL must be provided").toBeTruthy();
      }
      return { body: options.report ?? defaultImportReport };
    }
  };
}

export function mock401(pattern: string, message = "Unauthorized"): MockApiHandlers {
  return {
    [pattern]: {
      status: 401,
      body: { message }
    }
  };
}
