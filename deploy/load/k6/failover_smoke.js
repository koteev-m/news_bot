import http from 'k6/http';
import { check, sleep } from 'k6';
export const options = {
  vus: __ENV.VUS ? parseInt(__ENV.VUS) : 5,
  duration: __ENV.DURATION || '5m',
  thresholds: {
    http_req_failed: ['rate<0.05'],       // допускаем до 5% ошибок во время переключения
    http_req_duration: ['p(95)<2500']     // p95 <= 2.5s в смоке
  }
};

export default function () {
  const base = __ENV.BASE_URL;
  const r = http.get(`${base}/api/quotes/closeOrLast?instrumentId=1&date=2025-01-01`);
  check(r, { 'ok|404': (res) => res.status === 200 || res.status === 404 });
  sleep(1);
}
