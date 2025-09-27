interface TelegramNamespace {
  WebApp?: {
    initData?: string;
    initDataUnsafe?: Record<string, unknown>;
    ready?: () => void;
    expand?: () => void;
    [key: string]: unknown;
  };
  [key: string]: unknown;
}

function resolveNamespace(): TelegramNamespace | undefined {
  if (typeof window === "undefined") {
    return undefined;
  }
  const candidate = (window as Window & { Telegram?: unknown }).Telegram;
  if (candidate && typeof candidate === "object") {
    return candidate as TelegramNamespace;
  }
  return undefined;
}

export interface StartParam {
  tab?: string;
  [key: string]: string | undefined;
}

export type InitDataUnsafe = Record<string, unknown> & {
  user?: {
    id?: number;
    username?: string;
    first_name?: string;
    last_name?: string;
  };
  start_param?: string;
  startParam?: string;
};

export function getWebApp(): TelegramNamespace["WebApp"] | null {
  return resolveNamespace()?.WebApp ?? null;
}

export function ready(): void {
  const app = getWebApp();
  if (app?.ready) {
    app.ready();
  }
}

export function expand(): void {
  const app = getWebApp();
  if (app?.expand) {
    app.expand();
  }
}

export function getInitData(): string {
  return getWebApp()?.initData ?? "";
}

export function getInitDataUnsafe<T = InitDataUnsafe>(): T | null {
  return (getWebApp()?.initDataUnsafe as T | undefined) ?? null;
}

export function getStartParamRaw(): string {
  const unsafe = getInitDataUnsafe();
  if (!unsafe) {
    return "";
  }
  const candidate = (unsafe as InitDataUnsafe).start_param ?? (unsafe as InitDataUnsafe).startParam;
  return typeof candidate === "string" ? candidate : "";
}

export function parseStartParam(raw?: string): StartParam {
  const source = raw ?? getStartParamRaw();
  if (!source) {
    return {};
  }
  const params = new URLSearchParams(source);
  const result: StartParam = {};
  params.forEach((value, key) => {
    result[key] = value;
  });
  return result;
}
