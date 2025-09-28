import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';
import { baseUrl, guardNonProd, headersAuth, jwt, rndPortfolioId } from './helpers.js';

const scenarioFilter = (__ENV.K6_SCENARIO || '').trim();
const shortRun = (__ENV.K6_SHORT_RUN || '').toLowerCase() === 'true';

function buildOptions() {
  const base = {
    thresholds: {
      http_req_failed: ['rate<0.01'],
      'http_req_duration{scenario:ramp}': ['p(95)<750'],
    },
    scenarios: {
      smoke: {
        executor: 'constant-vus',
        vus: 1,
        duration: shortRun ? '5s' : '30s',
        exec: 'portfolioScenario',
        tags: { scenario: 'smoke' },
      },
      ramp: {
        executor: 'ramping-arrival-rate',
        startRate: shortRun ? 2 : 5,
        timeUnit: '1s',
        stages: shortRun
          ? [
              { duration: '30s', target: 10 },
              { duration: '30s', target: 10 },
              { duration: '10s', target: 0 },
            ]
          : [
              { duration: '2m', target: 30 },
              { duration: '3m', target: 30 },
              { duration: '1m', target: 0 },
            ],
        preAllocatedVUs: shortRun ? 10 : 20,
        maxVUs: shortRun ? 20 : 50,
        exec: 'portfolioScenario',
        tags: { scenario: 'ramp' },
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

export function setup() {
  guardNonProd();
  const dryRun = (__ENV.K6_DRY_RUN || '').toLowerCase() === 'true';
  return {
    apiBase: baseUrl(),
    hasJwt: !!jwt(),
    dryRun,
  };
}

function checkSkipped(reason) {
  check(null, { [reason]: () => true }, { scenario: exec.scenario.name, skip: reason });
}

function ensureJson(res) {
  try {
    return res.json();
  } catch (error) {
    return null;
  }
}

function getPortfolioList(apiBase, hasJwt) {
  if (!hasJwt) {
    checkSkipped('portfolio list skipped (missing JWT)');
    return;
  }

  const response = http.get(`${apiBase}/api/portfolio`, {
    headers: headersAuth({ Accept: 'application/json' }),
  });

  const body = ensureJson(response);

  check(response, {
    'portfolio list status 200': (res) => res.status === 200,
    'portfolio list json object': () => body !== null && typeof body === 'object',
  });

  if (body && typeof body === 'object') {
    check(body, {
      'portfolio list has items or array': (data) =>
        Array.isArray(data) || (Array.isArray(data.items) && data.items.length >= 0),
    });
  }
}

function getPortfolioPositions(apiBase) {
  const portfolioId = rndPortfolioId();
  if (!portfolioId) {
    checkSkipped('positions skipped (no portfolio id)');
    return;
  }

  const response = http.get(`${apiBase}/api/portfolio/${portfolioId}/positions`, {
    headers: headersAuth({ Accept: 'application/json' }),
  });

  const body = ensureJson(response);

  check(response, {
    'positions status 200': (res) => res.status === 200,
    'positions json object': () => body !== null && typeof body === 'object',
  });

  if (body && typeof body === 'object') {
    check(body, {
      'positions contains items array': (data) => Array.isArray(data.items),
    });
  }
}

function getPortfolioTrades(apiBase) {
  const portfolioId = rndPortfolioId();
  if (!portfolioId) {
    checkSkipped('trades skipped (no portfolio id)');
    return;
  }

  const response = http.get(`${apiBase}/api/portfolio/${portfolioId}/trades?limit=50&offset=0`, {
    headers: headersAuth({ Accept: 'application/json' }),
    tags: { request: 'trades' },
  });

  const body = ensureJson(response);

  check(response, {
    'trades status 200': (res) => res.status === 200,
    'trades json object': () => body !== null && typeof body === 'object',
  });

  if (body && typeof body === 'object') {
    check(body, {
      'trades contains pagination fields': (data) =>
        typeof data.total === 'number' && Array.isArray(data.items),
    });
  }
}

export function portfolioScenario(data) {
  if (data.dryRun) {
    checkSkipped('portfolio scenario skipped (dry run)');
    sleep(1);
    return;
  }

  const operations = [
    () => getPortfolioList(data.apiBase, data.hasJwt),
    () => getPortfolioPositions(data.apiBase),
    () => getPortfolioTrades(data.apiBase),
  ];

  const choice = operations[Math.floor(Math.random() * operations.length)];
  choice();
  sleep(1);
}

export default function (data) {
  portfolioScenario(data);
}
