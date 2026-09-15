import { check, fail } from 'k6';
import { newIntentKey } from '../lib/identity.js';
import { identityPool, loginIdentity } from '../lib/identity-pool.js';

export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'] } };

export default function () {
  const pool = identityPool(50001);
  let loginCalls = 0;
  const login = (index) => { loginCalls++; return `test-only-${index}`; };
  const first = loginIdentity(pool, 0, login);
  const last = loginIdentity(pool, 50000, login);
  let exhausted = false;
  try { loginIdentity(pool, 50001, login); } catch (_) { exhausted = true; }
  check(null, {
    'default target pool covers 50000 arrivals plus boundary': () => pool.size === 50001,
    'setup metadata stays below 128 bytes per VU': () => JSON.stringify(pool).length < 128,
    '1000 VUs do not replicate token pool': () => JSON.stringify(pool).length * 1000 < 128000 && !pool.tokens,
    'unique identities remain distinct': () => first !== last,
    'exhaustion fails before authentication and never wraps': () => exhausted && loginCalls === 2,
    'login does not retain credentials in pool': () => !JSON.stringify(pool).includes('test-only-'),
  });
  const stageOne = newIntentKey('run-regression-001', 101, 'seckill-new-intent', 0);
  const stageTwo = newIntentKey('run-regression-001', 102, 'seckill-new-intent', 0);
  const replayOne = newIntentKey('run-regression-001', 103, 'idempotency-replay', 0);
  const replayTwo = newIntentKey('run-regression-001', 103, 'idempotency-replay', 0);
  const passed = check(null, {
    'iteration zero differs across stages': () => stageOne !== stageTwo,
    'intentional replay remains identical': () => replayOne === replayTwo,
    'activity identity is present': () => stageOne.includes(':101:'),
  });
  if (!passed) fail('TASK-20 idempotency identity regression failed');
}
