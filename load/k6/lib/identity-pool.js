// No credentials or token arrays cross the setup/VU boundary in target-5k.
export function identityPool(size) {
  if (!Number.isSafeInteger(size) || size < 1) throw new Error('invalid identity pool size');
  return { mode: 'iteration-login', size };
}

export function loginIdentity(pool, index, login) {
  if (pool.mode !== 'iteration-login' || !Number.isSafeInteger(index) || index < 0 || index >= pool.size) {
    throw new Error('unique identity pool exhausted or invalid index');
  }
  // Deliberately no modulo, cache, or shared user: every new intent owns one identity.
  const token = login(index);
  if (typeof token !== 'string' || !token) throw new Error('identity login returned no token');
  return token;
}
