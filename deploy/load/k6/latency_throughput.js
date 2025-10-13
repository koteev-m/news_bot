import http from 'k6/http';
import { check, Trend } from 'k6';
export const options = {
  vus: __ENV.VUS ? parseInt(__ENV.VUS) : 10,
  duration: __ENV.DURATION || '2m',
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<1500']
  }
};
const tQuotes = new Trend('quotes_duration');
export default function () {
  const base = __ENV.BASE_URL;
  const r = http.get(`${base}/api/quotes/closeOrLast?instrumentId=1&date=2025-01-01`);
  tQuotes.add(r.timings.duration);
  check(r, { 'ok or 404': (res) => res.status === 200 || res.status === 404 });
}
