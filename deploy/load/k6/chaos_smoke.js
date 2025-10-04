import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const expectChaos = String(__ENV.EXPECT_CHAOS || '').toLowerCase() === 'true';
const latencyThreshold = Number(__ENV.CHAOS_LATENCY_THRESHOLD_MS || 150);

const thresholds = {
  http_req_failed: ['rate<0.3'],
};
if (expectChaos) {
  thresholds.chaos_injected_5xx = ['count>0'];
  thresholds.chaos_injected_latency = ['avg>0'];
}

const chaosErrors = new Counter('chaos_injected_5xx');
const chaosLatency = new Trend('chaos_injected_latency', true);

export const options = {
  scenarios: {
    smoke: { executor: 'constant-vus', vus: 1, duration: '20s' },
  },
  thresholds,
};

const BASE = __ENV.BASE_URL || '';
if (!BASE) {
  throw new Error('BASE_URL environment variable is required');
}
const JWT = __ENV.JWT || '';
const TG_SECRET = __ENV.TG_WEBHOOK_SECRET || '';
const authHeaders = JWT ? { Authorization: `Bearer ${JWT}` } : {};
const webhookHeaders = TG_SECRET
  ? { ...authHeaders, 'X-Telegram-Bot-Api-Secret-Token': TG_SECRET }
  : { ...authHeaders };

export default function () {
  const portfolio = http.get(`${BASE}/api/portfolio`, { headers: authHeaders });
  trackChaos(portfolio);
  check(portfolio, {
    'portfolio status ok/chaos': (r) => [200, 401, 403].includes(r.status) || r.status >= 500,
  });

  const positions = http.get(`${BASE}/api/portfolio/positions`, { headers: authHeaders });
  trackChaos(positions);
  check(positions, {
    'positions status ok/chaos': (r) => [200, 401, 403, 404].includes(r.status) || r.status >= 500,
  });

  const webhookPayload = JSON.stringify({ update_id: 1, message: { message_id: 1 } });
  const webhook = http.post(`${BASE}/telegram/webhook`, webhookPayload, {
    headers: { 'Content-Type': 'application/json', ...webhookHeaders },
  });
  trackChaos(webhook);
  check(webhook, {
    'webhook status ok/forbidden/chaos': (r) => [200, 401, 403].includes(r.status) || r.status >= 500,
  });

  sleep(1);
}

function trackChaos(response) {
  if (response.status >= 500) {
    chaosErrors.add(1);
  }
  if (response.timings.duration >= latencyThreshold) {
    chaosLatency.add(response.timings.duration);
  }
}
