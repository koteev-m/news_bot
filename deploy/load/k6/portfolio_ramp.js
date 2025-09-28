import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { baseUrl, headersAuth, rndPortfolioId, guardNonProd } from './helpers.js';

export const options = {
  stages: [
    { duration: '2m', target: 10 },
    { duration: '6m', target: 30 },
    { duration: '2m', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<750'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  guardNonProd();
  return {
    baseUrl: baseUrl(),
    headers: headersAuth({ Accept: 'application/json' }),
  };
}

function extractPortfolioId(responseJson) {
  if (!Array.isArray(responseJson)) {
    return null;
  }
  const candidate = responseJson.find((item) => item && item.id);
  return candidate ? candidate.id : null;
}

export default function main(data) {
  const { baseUrl, headers } = data;
  const listRes = http.get(`${baseUrl}/api/portfolio`, { headers });
  check(listRes, {
    'list portfolios 200': (res) => res.status === 200,
  });

  let portfolioId = rndPortfolioId();
  if (!portfolioId) {
    try {
      portfolioId = extractPortfolioId(listRes.json());
    } catch (error) {
      portfolioId = null;
    }
  }

  if (portfolioId) {
    group('portfolio positions', () => {
      const positionsRes = http.get(`${baseUrl}/api/portfolio/${portfolioId}/positions`, {
        headers,
      });
      check(positionsRes, {
        'positions 200': (res) => res.status === 200,
      });
    });

    group('portfolio trades', () => {
      const tradesRes = http.get(
        `${baseUrl}/api/portfolio/${portfolioId}/trades?limit=50&offset=0`,
        { headers },
      );
      check(tradesRes, {
        'trades 200': (res) => res.status === 200,
      });
    });
  }

  sleep(1);
}
