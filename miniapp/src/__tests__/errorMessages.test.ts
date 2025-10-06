import { beforeAll, describe, expect, it } from "vitest";
import { i18n } from "../i18n";
import { ApiError, parseApiErrorResponse, resolveErrorMessage, statusToErrorCode } from "../lib/errorMessages";

describe("errorMessages", () => {
  beforeAll(async () => {
    await i18n.changeLanguage("en");
  });

  it("formats payload too large details", () => {
    const error = new ApiError({ code: "PAYLOAD_TOO_LARGE", details: ["limit=2097152"] });
    const message = resolveErrorMessage(error, i18n.t.bind(i18n));
    expect(message).toContain("2 MB");
  });

  it("adds trace identifier for internal errors", () => {
    const error = new ApiError({ code: "INTERNAL", traceId: "abc123" });
    const message = resolveErrorMessage(error, i18n.t.bind(i18n));
    expect(message).toContain("#abc123");
  });

  it("parses API error payload", () => {
    const payload = parseApiErrorResponse('{"code":"BAD_REQUEST","message":"Invalid"}');
    expect(payload).toEqual({ code: "BAD_REQUEST", message: "Invalid" });
  });

  it("maps status to known error code", () => {
    expect(statusToErrorCode(401)).toBe("UNAUTHORIZED");
    expect(statusToErrorCode(409)).toBe("BILLING_DUPLICATE");
    expect(statusToErrorCode(418)).toBeNull();
  });
});
