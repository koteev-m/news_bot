import { fail } from 'k6';
import { SharedArray } from 'k6/data';

const csvData = new SharedArray('portfolioIds', () => {
  const raw = open('./data/portfolios.csv');
  const lines = raw.trim().split(/\r?\n/);
  const header = lines.shift();
  if (!header) {
    return [];
  }
  const idx = header.split(',').findIndex((col) => col.trim() === 'portfolio_id');
  if (idx === -1) {
    return [];
  }
  return lines
    .map((line) => line.split(',')[idx])
    .map((value) => value?.trim())
    .filter((value) => value);
});

export function baseUrl() {
  const url = (__ENV.BASE_URL || '').trim();
  if (!url) {
    fail('BASE_URL environment variable is required');
  }
  return url.replace(/\/$/, '');
}

export function jwt() {
  const token = (__ENV.JWT || '').trim();
  return token ? token : null;
}

export function headersAuth(additional = {}) {
  const headers = { ...additional };
  const token = jwt();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  if (!headers['Content-Type'] && !headers['content-type']) {
    headers['Content-Type'] = 'application/json';
  }
  return headers;
}

export function rndPortfolioId() {
  const envId = (__ENV.PORTFOLIO_ID || '').trim();
  if (envId) {
    return envId;
  }
  if (!csvData.length) {
    return null;
  }
  const index = Math.floor(Math.random() * csvData.length);
  return csvData[index];
}

export function guardNonProd() {
  if ((__ENV.APP_PROFILE || '').trim().toLowerCase() === 'prod') {
    fail('Refusing to run load tests in production profile');
  }
}
