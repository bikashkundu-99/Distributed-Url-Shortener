import http from 'k6/http';
import { check, sleep } from 'k6';

// Set BASE_URL, TARGET_RPS and DURATION. Example: k6 run -e BASE_URL=http://localhost:8080 -e TARGET_RPS=500 loadtest/k6.js
const base = __ENV.BASE_URL || 'http://localhost:8080';
const targetRps = Number(__ENV.TARGET_RPS || 500);
const duration = __ENV.DURATION || '60s';
export const options = {
  scenarios: { redirects: { executor: 'constant-arrival-rate', rate: targetRps, timeUnit: '1s', duration, preAllocatedVUs: Math.max(100, targetRps / 2), maxVUs: Math.max(500, targetRps * 2) } },
  thresholds: { http_req_failed: ['rate<0.01'], http_req_duration: ['p(99)<1000'] },
};
export function setup() {
  const r = http.post(`${base}/api/urls`, JSON.stringify({url:'https://example.com/benchmark'}), {headers:{'Content-Type':'application/json'}});
  if (r.status !== 201) throw new Error(`short URL setup failed: ${r.status} ${r.body}`);
  return {url:r.json('short_url')};
}
export default function (data) {
  const r = http.get(data.url, { redirects: 0 });
  check(r, {'redirect is 307': x => x.status === 307});
}
