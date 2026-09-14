const required = [
  'BASE_URL', 'ACTIVITY_ID', 'DATA_SEED', 'USER_COUNT', 'INVENTORY', 'RATE', 'VUS',
  'PRE_ALLOCATED_VUS', 'MAX_VUS', 'WARMUP', 'DURATION', 'RUN_ID', 'PROFILE',
  'SCENARIO', 'USER_PASSWORD',
];

for (const name of required) {
  if (!__ENV[name] || String(__ENV[name]).trim() === '') {
    throw new Error(`${name} is required; refusing to use an implicit load-test default`);
  }
}

function positiveInteger(name, allowZero = false) {
  const value = Number(__ENV[name]);
  if (!Number.isInteger(value) || value < (allowZero ? 0 : 1)) {
    throw new Error(`${name} must be ${allowZero ? 'a non-negative' : 'a positive'} integer`);
  }
  return value;
}

if (!/^[a-z0-9][a-z0-9-]{5,63}$/.test(__ENV.RUN_ID)) {
  throw new Error('RUN_ID must be a low-cardinality lowercase identifier');
}

export const config = Object.freeze({
  baseUrl: __ENV.BASE_URL.replace(/\/$/, ''),
  agentBaseUrl: (__ENV.AGENT_BASE_URL || '').replace(/\/$/, ''),
  activityId: positiveInteger('ACTIVITY_ID'),
  seed: positiveInteger('DATA_SEED'),
  userCount: positiveInteger('USER_COUNT'),
  inventory: positiveInteger('INVENTORY'),
  rate: positiveInteger('RATE'),
  vus: positiveInteger('VUS'),
  preAllocatedVUs: positiveInteger('PRE_ALLOCATED_VUS'),
  maxVUs: positiveInteger('MAX_VUS'),
  warmup: __ENV.WARMUP,
  duration: __ENV.DURATION,
  runId: __ENV.RUN_ID,
  profile: __ENV.PROFILE,
  scenario: __ENV.SCENARIO,
  password: __ENV.USER_PASSWORD,
});

export function username(index) {
  return `load-${config.seed}-user-${String(index + 1).padStart(6, '0')}`;
}

export function tags(scenario, endpoint, intent) {
  return { testid: config.runId, profile: config.profile, scenario, endpoint, intent };
}
