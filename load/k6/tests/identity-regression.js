import { check, fail } from 'k6';
import { newIntentKey } from '../lib/identity.js';

export const options = { vus: 1, iterations: 1 };

export default function () {
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
