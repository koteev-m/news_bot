import { useCallback } from "react";
import { useTranslation } from "react-i18next";
import { resolveErrorMessage } from "../lib/errorMessages";
import { useToaster } from "./Toaster";

export function useErrorToast(): (error: unknown, options?: { fallback?: string }) => void {
  const { t } = useTranslation();
  const toaster = useToaster();

  return useCallback(
    (error: unknown, options?: { fallback?: string }) => {
      const message = resolveErrorMessage(error, t, options);
      toaster.notifyError(message);
    },
    [t, toaster],
  );
}
