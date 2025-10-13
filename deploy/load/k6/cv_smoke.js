import http from 'k6/http';
import { check, sleep } from 'k6';

export default function () {
  const base = __ENV.BASE_URL;
  const r1 = http.get(`${base}/healthz`);
  check(r1, { 'healthz 200': (r) => r.status === 200 });

  const r2 = http.get(`${base}/api/quotes/closeOrLast?instrumentId=1&date=2025-01-01`);
  check(r2, { 'quotes ok/404': (r) => r.status === 200 || r.status === 404 });

  sleep(1);
}
