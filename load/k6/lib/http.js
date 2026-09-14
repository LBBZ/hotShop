import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { tags } from './config.js';

export const accepted = new Counter('hotshop_business_accepted');
export const soldOut = new Counter('hotshop_business_sold_out');
export const rejected = new Counter('hotshop_business_rejected');
export const systemErrors = new Counter('hotshop_business_system_errors');
export const agentRuns = new Counter('hotshop_agent_runs');
export const agentAttempts = new Counter('hotshop_agent_attempts');
export const agentFailures = new Counter('hotshop_agent_failures');
export const newIntents = new Counter('hotshop_new_intents');
export const scenarioIterations = new Counter('hotshop_scenario_iterations');
export const newAccepted = new Counter('hotshop_new_accepted');
export const http2xx = new Counter('hotshop_http_2xx');
export const http4xx = new Counter('hotshop_http_4xx');
export const http5xx = new Counter('hotshop_http_5xx');
export const httpOther = new Counter('hotshop_http_other');
export const rateLimited = new Counter('hotshop_business_rate_limited');

const problemCounters = Object.freeze({
  IDEMPOTENCY_KEY_CONFLICT: new Counter('hotshop_problem_idempotency_key_conflict'),
  RATE_LIMITED: new Counter('hotshop_problem_rate_limited'),
  FLASH_SALE_ACTIVITY_NOT_FOUND: new Counter('hotshop_problem_flash_sale_activity_not_found'),
  FLASH_SALE_NOT_STARTED: new Counter('hotshop_problem_flash_sale_not_started'),
  FLASH_SALE_ENDED: new Counter('hotshop_problem_flash_sale_ended'),
  FLASH_SALE_NOT_ACTIVE: new Counter('hotshop_problem_flash_sale_not_active'),
  FLASH_SALE_SOLD_OUT: new Counter('hotshop_problem_flash_sale_sold_out'),
  FLASH_SALE_USER_LIMIT_REACHED: new Counter('hotshop_problem_flash_sale_user_limit_reached'),
  FLASH_SALE_INVALID_QUANTITY: new Counter('hotshop_problem_flash_sale_invalid_quantity'),
  TRANSACTION_RATE_LIMIT_UNAVAILABLE: new Counter('hotshop_problem_transaction_rate_limit_unavailable'),
  SECKILL_STATE_INVALID: new Counter('hotshop_problem_seckill_state_invalid'),
});
const unclassifiedProblem = new Counter('hotshop_problem_unclassified');

export function jsonHeaders(token, extra = {}) {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...extra };
}

export function parseJson(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

export function classifyReservation(response, scenario, intent) {
  const metricTags = tags(scenario, 'reservation-create', intent);
  observeHttp(response, metricTags);
  const body = parseJson(response);
  const problemCode = typeof body?.code === 'string' ? body.code : null;
  if (response.status >= 400) {
    (problemCounters[problemCode] || unclassifiedProblem).add(1, metricTags);
  }
  if (response.status === 202 && body && /^rsv_[0-9a-f]{32}$/.test(body.reservationNo || '')) {
    accepted.add(1, metricTags);
    if (intent === 'new') newAccepted.add(1, metricTags);
    return { kind: 'accepted', body, problemCode: null };
  }
  if (response.status === 409 && body && body.code === 'FLASH_SALE_SOLD_OUT') {
    soldOut.add(1, metricTags);
    return { kind: 'sold_out', body, problemCode };
  }
  if (response.status >= 500 || response.status === 0) {
    systemErrors.add(1, metricTags);
    return { kind: 'system_error', body, problemCode };
  }
  if (response.status === 429) rateLimited.add(1, metricTags);
  rejected.add(1, metricTags);
  return { kind: 'rejected', body, problemCode: problemCode || 'UNCLASSIFIED' };
}

export function assertResponse(response, expected, name) {
  observeHttp(response, response.tags || {});
  return check(response, { [name]: (r) => expected.includes(r.status) });
}

export function observeHttp(response, metricTags) {
  if (response.status >= 200 && response.status < 300) http2xx.add(1, metricTags);
  else if (response.status >= 400 && response.status < 500) http4xx.add(1, metricTags);
  else if (response.status >= 500 && response.status < 600) http5xx.add(1, metricTags);
  else httpOther.add(1, metricTags);
}
