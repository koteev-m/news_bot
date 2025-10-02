import { useTranslation } from "react-i18next";

export interface LoadingProps {
  variant?: "spinner" | "skeleton";
  labelKey?: string;
  className?: string;
}

export function Loading({ variant = "spinner", labelKey = "loading", className }: LoadingProps): JSX.Element {
  const { t } = useTranslation();
  const message = t(labelKey);

  if (variant === "skeleton") {
    return (
      <div className={className ? `${className} loading-skeleton` : "loading-skeleton"} aria-busy="true">
        <span className="sr-only">{message}</span>
      </div>
    );
  }

  return (
    <div
      className={className ? `${className} loading-spinner` : "loading-spinner"}
      role="status"
      aria-live="polite"
      aria-busy="true"
    >
      <span aria-hidden="true" />
      <span className="sr-only">{message}</span>
    </div>
  );
}
