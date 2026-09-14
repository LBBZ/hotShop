import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { config, tags, username } from '../lib/config.js';
import { newIntentKey } from '../lib/identity.js';
import {
  accepted, agentAttempts, agentFailures, agentRuns, assertResponse, classifyReservation, jsonHeaders, newIntents, parseJson,
  scenarioIterations,
} from '../lib/http.js';

const statusObservation = new Trend('hotshop_reservation_status_observed_ms', true);
const newIntentDuration = new Trend('hotshop_new_intent_duration_ms', true);
const readDuration = new Trend('hotshop_read_duration_ms', true);
const agentDuration = new Trend('hotshop_agent_run_duration_ms', true);
const loginBatchSize = 20;

function arrival(execName, rate = config.rate, startTime = config.warmup, duration = config.duration) {
  return {
    executor: 'constant-arrival-rate', exec: execName, rate, timeUnit: '1s',
    duration, preAllocatedVUs: config.preAllocatedVUs,
    maxVUs: config.maxVUs, startTime,
  };
}

function selectedScenarios() {
  const warmup = {
    warmup: arrival('warmupRead', Math.min(config.rate, 10), '0s', config.warmup),
  };
  switch (config.scenario) {
    case 'smoke':
      return { ...warmup, smoke: { executor: 'per-vu-iterations', exec: 'smoke', vus: config.vus, iterations: 1, startTime: config.warmup } };
    case 'read-baseline':
      return { ...warmup, read_baseline: arrival('readBaseline') };
    case 'seckill-new-intent':
    case 'oversell-boundary':
      return { ...warmup, seckill_new_intent: arrival('seckillNewIntent') };
    case 'idempotency-replay':
      return { ...warmup, idempotency_replay: { executor: 'per-vu-iterations', exec: 'idempotencyReplay', vus: config.vus, iterations: 1, startTime: config.warmup } };
    case 'mixed-e2e':
      return { ...warmup, mixed_e2e: arrival('mixedE2e') };
    case 'agent-isolation':
      return {
        ...warmup,
        agent: arrival('agentIsolation', Math.max(1, Math.floor(config.rate / 5))),
        transaction: arrival('seckillNewIntent', config.rate),
      };
    default:
      throw new Error(`Unsupported SCENARIO=${config.scenario}`);
  }
}

export const options = {
  setupTimeout: '30m', teardownTimeout: '2m', scenarios: selectedScenarios(),
  tags: { testid: config.runId, profile: config.profile },
  systemTags: ['status', 'method', 'name', 'scenario'],
  // Targets are evaluated by the orchestrator so a performance miss still produces a
  // successful measurement unless -RequirePerformanceTarget is explicitly requested.
  thresholds: {},
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const needsTokens = config.scenario !== 'read-baseline';
  if (!needsTokens) return { tokens: [], reservations: [] };

  // constant-arrival-rate may schedule an iteration exactly on the end boundary.
  // Keep one extra identity so that boundary never causes credential reuse.
  const iterations = Math.ceil(config.rate * seconds(config.duration)) + 1;
  const required = config.scenario === 'smoke' || config.scenario === 'idempotency-replay'
    ? config.vus
    : config.scenario === 'mixed-e2e'
      ? Math.ceil(config.rate * seconds(config.duration) * 0.30) + 1
      : iterations;
  if (required > config.userCount) {
    throw new Error(`dataset refusal: ${required} unique users required, only ${config.userCount} prepared`);
  }
  if ((config.scenario === 'seckill-new-intent' || config.scenario === 'agent-isolation') && required > config.inventory) {
    throw new Error(`inventory refusal: ${required} new intents exceed inventory ${config.inventory}`);
  }

  const tokens = [];
  for (let offset = 0; offset < required; offset += loginBatchSize) {
    const requests = [];
    for (let index = offset; index < Math.min(required, offset + loginBatchSize); index += 1) {
      requests.push({
        method: 'POST', url: `${config.baseUrl}/api/v1/auth/login`,
        body: JSON.stringify({ username: username(index), password: config.password }),
        params: { headers: { 'Content-Type': 'application/json' }, tags: tags('prepare', 'login', 'read'), redirects: 0 },
      });
    }
    const responses = http.batch(requests);
    for (const response of responses) {
      const body = parseJson(response);
      if (response.status !== 200 || !body || !body.accessToken) {
        throw new Error(`login preparation failed with HTTP ${response.status}; response body intentionally omitted`);
      }
      tokens.push(body.accessToken);
    }
  }
  return { tokens, reservations: [] };
}

function seconds(value) {
  const match = /^(\d+)(ms|s|m)$/.exec(value);
  if (!match) throw new Error(`Duration must use ms, s, or m: ${value}`);
  const number = Number(match[1]);
  return match[2] === 'm' ? number * 60 : match[2] === 'ms' ? number / 1000 : number;
}

function tokenAt(data, index) {
  if (index >= data.tokens.length) throw new Error(`unique user pool exhausted at iteration ${index}`);
  return data.tokens[index];
}

function reservationRequest(token, index, scenario, intent = 'new', key = null) {
  const idempotencyKey = key || newIntentKey(
    config.runId, config.activityId, scenario, index,
  );
  const response = http.post(
    `${config.baseUrl}/api/v1/flash-sales/${config.activityId}/reservations`,
    JSON.stringify({ quantity: 1 }),
    { headers: jsonHeaders(token, { 'Idempotency-Key': idempotencyKey }), tags: tags(scenario, 'reservation-create', intent) },
  );
  if (intent === 'new') {
    newIntents.add(1, tags(scenario, 'reservation-create', intent));
    newIntentDuration.add(response.timings.duration, tags(scenario, 'reservation-create', intent));
  }
  return response;
}

export function smoke(data) {
  scenarioIterations.add(1, tags('smoke', 'journey', 'new'));
  const index = exec.scenario.iterationInTest;
  const token = tokenAt(data, index);
  const activities = http.get(`${config.baseUrl}/api/v1/flash-sale-activities?limit=50`, { tags: tags('smoke', 'activity-list', 'read') });
  check(activities, { 'activity list structure': (r) => r.status === 200 && Array.isArray(parseJson(r)) });
  const response = reservationRequest(token, index, 'smoke');
  const result = classifyReservation(response, 'smoke', 'new');
  check(response, { 'reservation accepted': () => result.kind === 'accepted' });
  if (result.kind !== 'accepted') return;
  const started = Date.now();
  for (let attempt = 0; attempt < 60; attempt += 1) {
    const status = http.get(
      `${config.baseUrl}/api/v1/flash-sales/${config.activityId}/reservations/${result.body.reservationNo}`,
      { headers: jsonHeaders(token), tags: tags('smoke', 'reservation-status', 'read') },
    );
    const body = parseJson(status);
    if (status.status === 200 && body && body.status === 'ORDER_CREATED' && body.orderId) {
      statusObservation.add(Date.now() - started, tags('smoke', 'reservation-status', 'read'));
      return;
    }
    sleep(0.25);
  }
  check(null, { 'async order observed': () => false });
}

export function readBaseline() {
  scenarioIterations.add(1, tags('read-baseline', 'activity-list', 'read'));
  const response = http.get(`${config.baseUrl}/api/v1/flash-sale-activities?limit=12`, { tags: tags('read-baseline', 'activity-list', 'read') });
  readDuration.add(response.timings.duration, tags('read-baseline', 'activity-list', 'read'));
  assertResponse(response, [200], 'read baseline succeeds');
}

export function warmupRead() {
  const response = http.get(`${config.baseUrl}/api/v1/flash-sale-activities?limit=12`, {
    tags: tags('warmup', 'activity-list', 'read'),
  });
  assertResponse(response, [200], 'warmup read succeeds');
}

export function seckillNewIntent(data) {
  scenarioIterations.add(1, tags(config.scenario, 'reservation-create', 'new'));
  const index = exec.scenario.iterationInTest;
  const response = reservationRequest(tokenAt(data, index), index, config.scenario, 'new');
  const result = classifyReservation(response, config.scenario, 'new');
  check(response, {
    'new intent has no idempotency conflict': () => result.problemCode !== 'IDEMPOTENCY_KEY_CONFLICT',
  });
}

export function idempotencyReplay(data) {
  scenarioIterations.add(1, tags('idempotency-replay', 'reservation-create', 'replay'));
  const index = exec.scenario.iterationInTest;
  const key = newIntentKey(config.runId, config.activityId, 'idempotency-replay', index);
  const first = reservationRequest(tokenAt(data, index), index, 'idempotency-replay', 'new', key);
  const original = classifyReservation(first, 'idempotency-replay', 'new');
  const replay = reservationRequest(tokenAt(data, index), index, 'idempotency-replay', 'replay', key);
  const repeated = classifyReservation(replay, 'idempotency-replay', 'replay');
  check(replay, {
    'replay returns original reservation': () => original.kind === 'accepted' && repeated.kind === 'accepted'
      && original.body.reservationNo === repeated.body.reservationNo
      && replay.headers['Idempotency-Replayed'] === 'true',
  });
}

export function mixedE2e(data) {
  scenarioIterations.add(1, tags('mixed-e2e', 'mixed', 'read'));
  const iteration = exec.scenario.iterationInTest;
  const bucket = iteration % 100;
  if (bucket < 50) {
    const response = http.get(`${config.baseUrl}/api/v1/flash-sale-activities?limit=12`, {
      tags: tags('mixed-e2e', 'activity-list', 'read'),
    });
    readDuration.add(response.timings.duration, tags('mixed-e2e', 'activity-list', 'read'));
    assertResponse(response, [200], 'mixed public read succeeds');
  } else if (bucket < 80) {
    const index = Math.floor(iteration / 100) * 30 + (bucket - 50);
    const response = reservationRequest(tokenAt(data, index), index, 'mixed-e2e', 'new');
    const result = classifyReservation(response, 'mixed-e2e', 'new');
    check(response, {
      'mixed new intent has no idempotency conflict': () => result.problemCode !== 'IDEMPOTENCY_KEY_CONFLICT',
    });
  } else {
    // A bounded authenticated read is used when no prior Reservation belongs to this VU.
    const response = http.get(`${config.baseUrl}/api/v1/users/me`, {
      headers: jsonHeaders(tokenAt(data, iteration % data.tokens.length)),
      tags: tags('mixed-e2e', 'user-profile', 'read'),
    });
    assertResponse(response, [200], 'mixed authenticated read succeeds');
  }
}

export function agentIsolation(data) {
  scenarioIterations.add(1, tags('agent-isolation', 'agent-run', 'read'));
  agentAttempts.add(1, tags('agent-isolation', 'agent-run', 'read'));
  const started = Date.now();
  const token = data.tokens[exec.scenario.iterationInTest % data.tokens.length];
  const auth = { headers: jsonHeaders(token), tags: tags('agent-isolation', 'agent-session', 'read') };
  const sessionResponse = http.post(`${config.agentBaseUrl}/api/v1/agent/sessions`, JSON.stringify({ scopes: ['catalog:read'] }), auth);
  const session = parseJson(sessionResponse);
  if (!check(sessionResponse, { 'agent session created': (r) => r.status === 201 && session && session.id })) {
    agentFailures.add(1, tags('agent-isolation', 'agent-run', 'read'));
    return;
  }
  const messageResponse = http.post(
    `${config.agentBaseUrl}/api/v1/agent/sessions/${session.id}/messages`,
    JSON.stringify({ content: '请查看当前在售商品。' }),
    { headers: jsonHeaders(token), tags: tags('agent-isolation', 'agent-message', 'read') },
  );
  const message = parseJson(messageResponse);
  if (!check(messageResponse, { 'agent message created': (r) => r.status === 201 && message && message.id })) {
    agentFailures.add(1, tags('agent-isolation', 'agent-run', 'read'));
    return;
  }
  const runResponse = http.post(
    `${config.agentBaseUrl}/api/v1/agent/sessions/${session.id}/runs`,
    JSON.stringify({ messageId: message.id }),
    { headers: jsonHeaders(token), tags: tags('agent-isolation', 'agent-run', 'read') },
  );
  const run = parseJson(runResponse);
  if (!check(runResponse, { 'agent run accepted': (r) => r.status === 202 && run && run.id })) {
    agentFailures.add(1, tags('agent-isolation', 'agent-run', 'read'));
    return;
  }
  const events = http.get(`${config.agentBaseUrl}/api/v1/agent/runs/${run.id}/events`, {
    headers: jsonHeaders(token), tags: tags('agent-isolation', 'agent-events-complete-stream', 'read'), timeout: '30s',
  });
  if (check(events, { 'agent SSE completes': (r) => r.status === 200 && r.body.includes('event: done') })) {
    agentRuns.add(1, tags('agent-isolation', 'agent-run', 'read'));
    agentDuration.add(Date.now() - started, tags('agent-isolation', 'agent-run', 'read'));
  } else {
    agentFailures.add(1, tags('agent-isolation', 'agent-run', 'read'));
  }
}

export function handleSummary(data) {
  const redacted = JSON.stringify(data, null, 2);
  return { '/artifacts/raw-summary.json': redacted, stdout: `TASK-20 k6 scenario ${config.scenario} complete\n` };
}
