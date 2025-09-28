import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';
import { baseUrl, guardNonProd } from './helpers.js';

const scenarioFilter = (__ENV.K6_SCENARIO || '').trim();
const shortRun = (__ENV.K6_SHORT_RUN || '').toLowerCase() === 'true';

function buildOptions() {
  const base = {
    thresholds: {
      http_req_failed: ['rate<0.01'],
      'http_req_duration{scenario:burst}': ['p(95)<1500'],
    },
    scenarios: {
      smoke: {
        executor: 'constant-vus',
        vus: 1,
        duration: shortRun ? '5s' : '15s',
        exec: 'webhookScenario',
        tags: { scenario: 'smoke' },
      },
      burst: {
        executor: 'ramping-arrival-rate',
        startRate: shortRun ? 2 : 5,
        timeUnit: '1s',
        stages: shortRun
          ? [
              { duration: '20s', target: 10 },
              { duration: '20s', target: 10 },
              { duration: '10s', target: 0 },
            ]
          : [
              { duration: '60s', target: 20 },
              { duration: '60s', target: 20 },
              { duration: '30s', target: 0 },
            ],
        preAllocatedVUs: shortRun ? 10 : 20,
        maxVUs: shortRun ? 20 : 40,
        exec: 'webhookScenario',
        tags: { scenario: 'burst' },
      },
    },
  };

  if (!scenarioFilter) {
    return base;
  }

  if (!base.scenarios[scenarioFilter]) {
    fail(`Unknown scenario requested via K6_SCENARIO: ${scenarioFilter}`);
  }

  return {
    thresholds: base.thresholds,
    scenarios: {
      [scenarioFilter]: base.scenarios[scenarioFilter],
    },
  };
}

export const options = buildOptions();

function requiredEnv(name) {
  const value = (__ENV[name] || '').trim();
  if (!value) {
    fail(`${name} environment variable is required for webhook tests`);
  }
  return value;
}

export function setup() {
  guardNonProd();
  const dryRun = (__ENV.K6_DRY_RUN || '').toLowerCase() === 'true';
  const apiBase = baseUrl();
  if (dryRun) {
    return {
      apiBase,
      dryRun: true,
      userId: null,
      secret: null,
    };
  }
  return {
    apiBase,
    userId: requiredEnv('TG_USER_ID'),
    secret: requiredEnv('WEBHOOK_SECRET'),
    dryRun: false,
  };
}

function buildPayload(userId) {
  const numericUserId = Number(userId);
  const repeatWindow = 5;
  const iteration = exec.scenario.iterationInTest;
  const shouldRepeat = iteration % repeatWindow === repeatWindow - 1;
  const uniqueSuffix = `${__VU}_${Date.now()}_${iteration}`;
  const providerChargeId = shouldRepeat ? 'pmt_k6_repeat_static' : `pmt_k6_${uniqueSuffix}`;

  const now = Math.floor(Date.now() / 1000);

  return {
    update_id: iteration + 1000,
    message: {
      message_id: iteration + 1,
      date: now,
      chat: {
        id: numericUserId,
        type: 'private',
      },
      from: {
        id: numericUserId,
        is_bot: false,
        first_name: 'k6',
        language_code: 'en',
      },
      successful_payment: {
        currency: 'XTR',
        total_amount: 1234,
        invoice_payload: `${userId}:PRO:abc123`,
        provider_payment_charge_id: providerChargeId,
        telegram_payment_charge_id: `telegram_${providerChargeId}`,
      },
    },
  };
}

export function webhookScenario(data) {
  if (data.dryRun) {
    check(null, { 'webhook scenario skipped (dry run)': () => true }, { scenario: exec.scenario.name, skip: 'dry-run' });
    sleep(1);
    return;
  }

  const payload = buildPayload(data.userId);
  const response = http.post(
    `${data.apiBase}/telegram/webhook`,
    JSON.stringify(payload),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-Telegram-Bot-Api-Secret-Token': data.secret,
      },
      tags: { scenario: exec.scenario.name },
    },
  );

  check(response, {
    'webhook status 200': (res) => res.status === 200,
  });

  sleep(1);
}

export default function (data) {
  webhookScenario(data);
}
