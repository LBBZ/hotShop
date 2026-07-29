const requestIdPattern = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/u;

export function createRequestId(): string {
  return crypto.randomUUID();
}

export function withRequestId(headersInit?: HeadersInit): Headers {
  const headers = new Headers(headersInit);
  const supplied = headers.get("X-Request-Id");
  if (!supplied || !requestIdPattern.test(supplied)) {
    headers.set("X-Request-Id", createRequestId());
  }
  return headers;
}
