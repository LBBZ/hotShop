const traceparentPattern = /^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$/u;
const tracestatePattern = /^[\x20-\x7e]{1,512}$/u;

function randomHex(bytes: number): string {
  const value = new Uint8Array(bytes);
  do {
    crypto.getRandomValues(value);
  } while (value.every((item) => item === 0));
  return Array.from(value, (item) => item.toString(16).padStart(2, "0")).join(
    "",
  );
}

export function createTraceparent(): string {
  return `00-${randomHex(16)}-${randomHex(8)}-01`;
}

export function withTraceContext(headersInit?: HeadersInit): Headers {
  const headers = new Headers(headersInit);
  const supplied = headers.get("traceparent");
  if (!supplied || !traceparentPattern.test(supplied)) {
    headers.set("traceparent", createTraceparent());
  }
  const tracestate = headers.get("tracestate");
  if (tracestate && !tracestatePattern.test(tracestate)) {
    headers.delete("tracestate");
  }
  return headers;
}
