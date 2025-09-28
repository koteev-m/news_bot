import http from 'k6/http';
import { check } from 'k6';
import { fail } from 'k6';
import { baseUrl, guardNonProd } from './helpers.js';

export const options = {
  scenarios: {
    burst: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1s',
      stages: [
        { target: 40, duration: '2m30s' },
        { target: 60, duration: '1m' },
        { target: 0, duration: '1m30s' },
      ],
      preAllocatedVUs: 50,
      maxVUs: 100,
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<1500'],
    http_req_failed: ['rate<0.01'],
  },
};

function requireEnv(name) {
  const value = (__ENV[name] || '').trim();
  if (!value) {
    fail(`${name} environment variable is required`);
  }
  return value;
}

export function setup() {
  guardNonProd();
  return {
    baseUrl: baseUrl(),
    secret: requireEnv('WEBHOOK_SECRET'),
    tgUserId: requireEnv('TG_USER_ID'),
  };
}

function buildHeaders(secret) {
  return {
    'Content-Type': 'application/json',
    'X-Telegram-Bot-Api-Secret-Token': secret,
  };
}

function uniqueProviderId(iteration) {
  if (iteration % 7 === 0) {
    return `pmt_repeat_${__VU}_${Math.floor(iteration / 7)}`;
  }
  const timestamp = Date.now();
  return `pmt_${__VU}_${iteration}_${timestamp}`;
}

export default function main(data) {
  const providerId = uniqueProviderId(__ITER);
  const userId = /^\d+$/.test(data.tgUserId) ? Number(data.tgUserId) : data.tgUserId;
  const payload = {
    message: {
      from: { id: userId },
      successful_payment: {
        currency: 'XTR',
        total_amount: 1234,
        invoice_payload: `${data.tgUserId}:PRO:${__VU}`,
        provider_payment_charge_id: providerId,
      },
    },
  };

  const res = http.post(`${data.baseUrl}/telegram/webhook`, JSON.stringify(payload), {
    headers: buildHeaders(data.secret),
  });

  check(res, {
    'webhook 200': (response) => response.status === 200,
  });
}
