import { clearJwt, getJwt, setJwt } from "./session";

const API_BASE = (import.meta.env.VITE_API_BASE ?? "").replace(/\/$/, "");
const DEFAULT_TIMEOUT = 10000;

export interface Position {
  instrumentId: string;
  qty: number;
  avgPrice: number;
  lastPrice: number;
  upl: number;
}

export interface Trade {
  id: string;
  instrumentId: string;
  qty: number;
  price: number;
  side: "buy" | "sell";
  executedAt: string;
}

export interface TradesResponse {
  items: Trade[];
  total: number;
  limit: number;
  offset: number;
}

export interface ImportResult {
  inserted: number;
  skipped: number;
  failed: Array<{ row: number; reason: string }>;
}

export interface ReportSummary {
  totalValue: number;
  realizedPnl: number;
  unrealizedPnl: number;
}

export interface PortfolioReport {
  summary: ReportSummary;
  generatedAt: string;
}

export interface Quote {
  instrumentId: string;
  price: number;
  changePct: number;
  updatedAt: string;
}

function withBase(path: string): string {
  if (!API_BASE) {
    return path;
  }
  if (path.startsWith("http")) {
    return path;
  }
  return `${API_BASE}${path}`;
}

function createUrl(path: string): URL {
  const base = API_BASE || (typeof window !== "undefined" ? window.location.origin : "http://localhost");
  return new URL(path, base);
}

async function request<T>(path: string, init: RequestInit = {}, options?: { requireAuth?: boolean; timeoutMs?: number }): Promise<T> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), options?.timeoutMs ?? DEFAULT_TIMEOUT);
  const headers = new Headers(init.headers);
  if ((options?.requireAuth ?? true) && !headers.has("Authorization")) {
    const token = getJwt();
    if (token) {
      headers.set("Authorization", `Bearer ${token}`);
    }
  }
  if (!headers.has("Content-Type") && init.body && !(init.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }
  try {
    const response = await fetch(withBase(path), {
      ...init,
      headers,
      signal: controller.signal
    });
    if (response.status === 401) {
      clearJwt();
      throw new Error("Unauthorized");
    }
    if (!response.ok) {
      const message = await safeReadError(response);
      throw new Error(message);
    }
    const text = await response.text();
    if (!text) {
      return undefined as T;
    }
    return JSON.parse(text) as T;
  } catch (error) {
    if ((error as Error).name === "AbortError") {
      throw new Error("Request timed out");
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

async function safeReadError(response: Response): Promise<string> {
  try {
    const text = await response.text();
    if (!text) {
      return response.statusText || "Request failed";
    }
    try {
      const data = JSON.parse(text) as { message?: string };
      return data.message ?? text;
    } catch (error) {
      return text;
    }
  } catch (error) {
    return response.statusText || "Request failed";
  }
}

export async function authWebApp(initData: string): Promise<string> {
  if (!initData) {
    throw new Error("Missing init data");
  }
  const tokenResponse = await request<{ token: string }>("/api/auth/telegram/verify", {
    method: "POST",
    body: JSON.stringify({ initData })
  }, { requireAuth: false });
  if (!tokenResponse.token) {
    throw new Error("Invalid auth response");
  }
  setJwt(tokenResponse.token);
  return tokenResponse.token;
}

export async function getPositions(): Promise<Position[]> {
  return request<Position[]>("/api/positions", { method: "GET" });
}

export async function getTrades(params: { limit?: number; offset?: number; side?: "buy" | "sell" | "all" } = {}): Promise<TradesResponse> {
  const url = createUrl("/api/trades");
  if (params.limit) {
    url.searchParams.set("limit", params.limit.toString());
  }
  if (typeof params.offset === "number") {
    url.searchParams.set("offset", params.offset.toString());
  }
  if (params.side && params.side !== "all") {
    url.searchParams.set("side", params.side);
  }
  return request<TradesResponse>(url.toString(), { method: "GET" });
}

export async function postImportCsv(file: File): Promise<ImportResult> {
  const form = new FormData();
  form.append("file", file);
  return request<ImportResult>("/api/import/csv", {
    method: "POST",
    body: form
  });
}

export async function postRevalue(): Promise<{ status: string }> {
  return request<{ status: string }>("/api/portfolio/revalue", { method: "POST" });
}

export async function getReport(params: { from?: string; to?: string } = {}): Promise<PortfolioReport> {
  const url = createUrl("/api/reports/portfolio");
  if (params.from) {
    url.searchParams.set("from", params.from);
  }
  if (params.to) {
    url.searchParams.set("to", params.to);
  }
  return request<PortfolioReport>(url.toString(), { method: "GET" });
}

export async function getQuotes(instruments?: string[]): Promise<Quote[]> {
  const url = createUrl("/api/quotes");
  if (instruments && instruments.length > 0) {
    url.searchParams.set("instruments", instruments.join(","));
  }
  return request<Quote[]>(url.toString(), { method: "GET" });
}
