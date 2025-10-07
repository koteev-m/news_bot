import http from 'k6/http';
import { check } from 'k6';

export const options = { vus: 1, duration: '10s' };

function uuid4() {
  // упрощённый UUID4 для корреляции
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export default function () {
  const id = uuid4();
  const params = { headers: { 'X-Request-Id': id } };
  const res = http.get(`${__ENV.BASE_URL}/healthz`, params);
  check(res, {
    '200': (response) => response.status === 200,
  });
}
