function requirePart(name, value) {
  const text = String(value ?? '').trim();
  if (!text) throw new Error(`${name} is required for an idempotency key`);
  return text;
}

export function newIntentKey(runId, activityId, scenario, iteration) {
  const index = Number(iteration);
  if (!Number.isInteger(index) || index < 0) {
    throw new Error('iteration must be a non-negative integer');
  }
  return `${requirePart('runId', runId)}:${requirePart('activityId', activityId)}:${requirePart('scenario', scenario)}:${String(index).padStart(12, '0')}`;
}
