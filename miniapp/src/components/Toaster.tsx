import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";

type ToastTone = "info" | "error";

interface ToastItem {
  id: number;
  message: string;
  tone: ToastTone;
}

interface ToasterContextValue {
  notify: (message: string) => void;
  notifyError: (message: string) => void;
  dismiss: (id: number) => void;
}

const ToasterContext = createContext<ToasterContextValue | null>(null);

const DISMISS_TIMEOUT = 6000;

function shouldFocusToast(): boolean {
  const activeElement = document.activeElement;
  if (!activeElement || activeElement === document.body) {
    return true;
  }
  if (activeElement instanceof HTMLElement) {
    const interactiveTags = new Set(["INPUT", "TEXTAREA", "SELECT", "BUTTON", "A"]);
    if (interactiveTags.has(activeElement.tagName)) {
      return false;
    }
  }
  return true;
}

export function ToasterProvider({ children }: { children: ReactNode }): JSX.Element {
  const { t } = useTranslation();
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const nextIdRef = useRef(1);
  const timersRef = useRef(new Map<number, number>());
  const lastToastRef = useRef<HTMLDivElement | null>(null);

  const clearTimer = useCallback((id: number) => {
    const timerId = timersRef.current.get(id);
    if (timerId) {
      window.clearTimeout(timerId);
      timersRef.current.delete(id);
    }
  }, []);

  const scheduleDismiss = useCallback(
    (id: number) => {
      clearTimer(id);
      const timerId = window.setTimeout(() => {
        setToasts((current) => current.filter((toast) => toast.id !== id));
        timersRef.current.delete(id);
      }, DISMISS_TIMEOUT);
      timersRef.current.set(id, timerId);
    },
    [clearTimer],
  );

  useEffect(() => {
    if (toasts.length === 0) {
      return;
    }
    const last = lastToastRef.current;
    if (last && shouldFocusToast()) {
      last.focus({ preventScroll: true });
    }
  }, [toasts]);

  useEffect(() => {
    return () => {
      timersRef.current.forEach((timerId) => window.clearTimeout(timerId));
      timersRef.current.clear();
    };
  }, []);

  const dismiss = useCallback(
    (id: number) => {
      clearTimer(id);
      setToasts((current) => current.filter((toast) => toast.id !== id));
    },
    [clearTimer],
  );

  const notifyInternal = useCallback((message: string, tone: ToastTone) => {
    setToasts((current) => {
      const id = nextIdRef.current++;
      const toast: ToastItem = { id, message, tone };
      scheduleDismiss(id);
      return [...current, toast];
    });
  }, [scheduleDismiss]);

  const value = useMemo<ToasterContextValue>(
    () => ({
      notify: (message: string) => notifyInternal(message, "info"),
      notifyError: (message: string) => notifyInternal(message, "error"),
      dismiss,
    }),
    [dismiss, notifyInternal],
  );

  return (
    <ToasterContext.Provider value={value}>
      {children}
      <div className="toaster" aria-live="polite" aria-atomic="true">
        {toasts.map((toast, index) => {
          const ref = index === toasts.length - 1 ? lastToastRef : undefined;
          return (
            <div
              key={toast.id}
              ref={ref}
              className={toast.tone === "error" ? "toast toast--error" : "toast"}
              role={toast.tone === "error" ? "alert" : "status"}
              tabIndex={-1}
            >
              <p>{toast.message}</p>
              <button type="button" onClick={() => dismiss(toast.id)} aria-label={t("toaster.dismiss")}>×</button>
            </div>
          );
        })}
      </div>
    </ToasterContext.Provider>
  );
}

export function useToaster(): ToasterContextValue {
  const context = useContext(ToasterContext);
  if (!context) {
    throw new Error("useToaster must be used within ToasterProvider");
  }
  return context;
}
