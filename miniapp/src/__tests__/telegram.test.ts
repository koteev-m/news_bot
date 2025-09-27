import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { getWebApp, parseStartParam } from "../lib/telegram";

describe("telegram helpers", () => {
  beforeEach(() => {
    delete window.Telegram;
  });

  afterEach(() => {
    delete window.Telegram;
  });

  it("returns null when Telegram WebApp is not available", () => {
    expect(getWebApp()).toBeNull();
  });

  it("parses start parameter payload", () => {
    const params = parseStartParam("tab=import&foo=bar");
    expect(params.tab).toBe("import");
    expect(params.foo).toBe("bar");
  });

  it("reads start_param from global WebApp", () => {
    (window as Window & { Telegram?: unknown }).Telegram = {
      WebApp: {
        initData: "",
        initDataUnsafe: {
          start_param: "tab=trades",
        },
        ready: () => {},
        expand: () => {},
        close: () => {},
        MainButton: {} as any,
        BackButton: {} as any,
      } as any,
    } as any;
    const params = parseStartParam();
    expect(params.tab).toBe("trades");
  });
});
