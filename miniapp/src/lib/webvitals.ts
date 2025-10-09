import { onCLS, onFID, onLCP, onINP, onTTFB, Metric } from "web-vitals";

type Sender = (name: string, value: number, page?: string, navType?: string) => void;

function send(metric: Metric, post: Sender) {
  const name = metric.name;
  const value = metric.value;
  const page = location.hash || "/";
  const navEntry = performance.getEntriesByType("navigation")[0] as PerformanceNavigationTiming | undefined;
  const navType = navEntry?.type || "navigate";
  post(name, value, page, navType);
}

export function initWebVitals(post: Sender) {
  onCLS((metric) => send(metric, post));
  onFID((metric) => send(metric, post));
  onLCP((metric) => send(metric, post));
  onINP((metric) => send(metric, post));
  onTTFB((metric) => send(metric, post));
}
