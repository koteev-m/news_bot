import { useEffect, useState } from "react";
import { authWebApp } from "./api";
import { clearJwt, getJwt } from "./session";

export type AuthStatus = "pending" | "authorized" | "unauthorized";

export interface AuthState {
  status: AuthStatus;
  error?: string;
}

export function useAuthGuard(initData: string | null, enabled: boolean): AuthState {
  const [state, setState] = useState<AuthState>(() => {
    if (getJwt()) {
      return { status: "authorized" };
    }
    return { status: "pending" };
  });

  useEffect(() => {
    let cancelled = false;
    const token = getJwt();
    if (token) {
      setState({ status: "authorized" });
      return;
    }
    if (!enabled) {
      setState({ status: "pending" });
      return;
    }
    if (!initData) {
      setState({ status: "unauthorized", error: "Mini App must be opened from Telegram." });
      return;
    }
    setState({ status: "pending" });
    authWebApp(initData)
      .then(() => {
        if (!cancelled) {
          setState({ status: "authorized" });
        }
      })
      .catch((error) => {
        if (!cancelled) {
          clearJwt();
          setState({ status: "unauthorized", error: (error as Error).message });
        }
      });
    return () => {
      cancelled = true;
    };
  }, [initData, enabled]);

  return state;
}

export function AuthRequired({ error }: { error?: string }): JSX.Element {
  return (
    <div className="auth-required">
      <h1>Authorization required</h1>
      <p>
        {error ? error : "This mini app needs to be launched from Telegram to receive a secure session."}
      </p>
      <p>Open the bot in Telegram and tap the Mini App button.</p>
    </div>
  );
}
