import type { TFunction } from "i18next";
import errorsEn from "../i18n/errors.en.json";

export const knownErrorCodes = [
  "BAD_REQUEST",
  "UNAUTHORIZED",
  "FORBIDDEN",
  "NOT_FOUND",
  "UNPROCESSABLE",
  "RATE_LIMITED",
  "PAYLOAD_TOO_LARGE",
  "UNSUPPORTED_MEDIA",
  "INTERNAL",
  "CSV_MAPPING_ERROR",
  "SELL_EXCEEDS_POSITION",
  "IMPORT_BY_URL_DISABLED",
  "CHAOS_INJECTED",
  "BILLING_DUPLICATE",
  "BILLING_APPLY_FAILED",
] as const;

export type KnownErrorCode = (typeof knownErrorCodes)[number];

export interface ApiErrorPayload {
  code: string;
  message?: string;
  details?: string[];
  traceId?: string;
}

export class ApiError extends Error {
  readonly code: string;
  readonly details: string[];
  readonly traceId: string;

  constructor(payload: ApiErrorPayload) {
    const normalizedCode = typeof payload.code === "string" && payload.code ? payload.code : "INTERNAL";
    super(payload.message || fallbackMessages[normalizeToKnownCode(normalizedCode)]);
    this.name = "ApiError";
    this.code = normalizedCode;
    this.details = Array.isArray(payload.details) ? payload.details.filter((item): item is string => typeof item === "string") : [];
    this.traceId = payload.traceId ?? "-";
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

export function parseApiErrorResponse(text: string): ApiErrorPayload | null {
  try {
    const data = JSON.parse(text) as unknown;
    if (!data || typeof data !== "object") {
      return null;
    }
    const maybeCode = (data as { code?: unknown }).code;
    if (typeof maybeCode !== "string" || maybeCode.length === 0) {
      return null;
    }
    const maybeMessage = (data as { message?: unknown }).message;
    const message = typeof maybeMessage === "string" ? maybeMessage : undefined;
    const maybeDetails = (data as { details?: unknown }).details;
    const details = Array.isArray(maybeDetails)
      ? maybeDetails.filter((item): item is string => typeof item === "string")
      : undefined;
    const maybeTraceId = (data as { traceId?: unknown }).traceId;
    const traceId = typeof maybeTraceId === "string" ? maybeTraceId : undefined;
    return { code: maybeCode, message, details, traceId };
  } catch (_error) {
    return null;
  }
}

export function resolveErrorMessage(error: unknown, t: TFunction, options?: { fallback?: string }): string {
  if (error instanceof ApiError) {
    const normalized = normalizeToKnownCode(error.code);
    const fallback = fallbackMessages[normalized];
    const params = buildParams(normalized, error.details);
    const translated = t(`errors:${normalized}`, {
      defaultValue: fallback,
      ...params,
    });
    if (normalized === "INTERNAL" && error.traceId && error.traceId !== "-") {
      return `${translated} (#${error.traceId})`;
    }
    return translated;
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return options?.fallback ?? fallbackMessages.INTERNAL;
}

export function statusToErrorCode(status: number): KnownErrorCode | null {
  switch (status) {
    case 400:
      return "BAD_REQUEST";
    case 401:
      return "UNAUTHORIZED";
    case 403:
      return "FORBIDDEN";
    case 404:
      return "NOT_FOUND";
    case 409:
      return "BILLING_DUPLICATE";
    case 413:
      return "PAYLOAD_TOO_LARGE";
    case 415:
      return "UNSUPPORTED_MEDIA";
    case 422:
      return "UNPROCESSABLE";
    case 429:
      return "RATE_LIMITED";
    case 500:
      return "INTERNAL";
    case 502:
      return "BILLING_APPLY_FAILED";
    case 503:
      return "IMPORT_BY_URL_DISABLED";
    default:
      return null;
  }
}

function normalizeToKnownCode(code: string): KnownErrorCode {
  const upper = code.toUpperCase();
  if (isKnownErrorCode(upper)) {
    return upper;
  }
  return "INTERNAL";
}

function isKnownErrorCode(code: string): code is KnownErrorCode {
  return (knownErrorCodes as readonly string[]).includes(code);
}

const fallbackMessages: Record<KnownErrorCode, string> = errorsEn as Record<KnownErrorCode, string>;

type TranslationParams = Record<string, unknown>;

function buildParams(code: KnownErrorCode, details: readonly string[]): TranslationParams {
  if (code === "PAYLOAD_TOO_LARGE") {
    const limit = extractLimit(details);
    if (limit !== null) {
      return { limit: formatBytes(limit) };
    }
  }
  if (code === "FORBIDDEN") {
    const required = extractDetail(details, "tier_required:");
    if (required) {
      return { requiredTier: required };
    }
  }
  return {};
}

function extractDetail(details: readonly string[], prefix: string): string | null {
  for (const detail of details) {
    if (detail.startsWith(prefix)) {
      return detail.slice(prefix.length);
    }
  }
  return null;
}

function extractLimit(details: readonly string[]): number | null {
  const value = extractDetail(details, "limit=");
  if (!value) {
    return null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function formatBytes(bytes: number): string {
  if (bytes <= 0) {
    return "0 B";
  }
  const units = ["B", "KB", "MB", "GB"] as const;
  let remaining = bytes;
  let unitIndex = 0;
  while (remaining >= 1024 && unitIndex < units.length - 1) {
    remaining /= 1024;
    unitIndex += 1;
  }
  const formatted = unitIndex === 0 ? remaining.toString() : remaining.toFixed(1);
  return `${trimTrailingZero(formatted)} ${units[unitIndex]}`;
}

function trimTrailingZero(value: string): string {
  return value.replace(/\.0$/, "");
}
