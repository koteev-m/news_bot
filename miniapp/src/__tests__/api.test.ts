import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Mock } from "vitest";
import { authWebApp } from "../lib/api";
import { clearJwt, getJwt } from "../lib/session";

type FetchMock = Mock<[RequestInfo | URL, RequestInit?], Promise<Response>>;

describe("authWebApp", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
    clearJwt();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    clearJwt();
  });

  it("stores JWT on successful verification", async () => {
    const mockFetch = globalThis.fetch as unknown as FetchMock;
    mockFetch.mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => JSON.stringify({ token: "jwt-token" })
    } as Response);

    const token = await authWebApp("init-data");

    expect(token).toBe("jwt-token");
    expect(getJwt()).toBe("jwt-token");
    expect(mockFetch).toHaveBeenCalledWith(
      "/api/auth/telegram/verify",
      expect.objectContaining({
        method: "POST"
      })
    );
  });

  it("throws on unauthorized response", async () => {
    const mockFetch = globalThis.fetch as unknown as FetchMock;
    mockFetch.mockResolvedValue({
      ok: false,
      status: 401,
      statusText: "Unauthorized",
      text: async () => ""
    } as Response);

    await expect(authWebApp("init-data")).rejects.toThrow("Unauthorized");
    expect(getJwt()).toBeNull();
  });

  it("throws on validation error", async () => {
    const mockFetch = globalThis.fetch as unknown as FetchMock;
    mockFetch.mockResolvedValue({
      ok: false,
      status: 400,
      statusText: "Bad Request",
      text: async () => JSON.stringify({ message: "Invalid init data" })
    } as Response);

    await expect(authWebApp("init-data")).rejects.toThrow("Invalid init data");
  });
});
