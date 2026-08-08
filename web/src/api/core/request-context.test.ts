import { describe, expect, it, vi } from "vitest";

import { publicFetch } from "@/api/core/public-fetch";
import { withRequestId } from "@/api/core/request-id";

describe("observable request context", () => {
  it("creates W3C trace context and a request ID without high-cardinality baggage", () => {
    const headers = withRequestId();

    expect(headers.get("X-Request-ID")).toMatch(/^[0-9a-f-]{36}$/u);
    expect(headers.get("traceparent")).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/u);
    expect(headers.has("baggage")).toBe(false);
  });

  it("preserves valid caller context and removes malformed tracestate", () => {
    const traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    const headers = withRequestId({
      "X-Request-ID": "browser-request-11",
      traceparent,
      tracestate: "a".repeat(513),
    });

    expect(headers.get("X-Request-ID")).toBe("browser-request-11");
    expect(headers.get("traceparent")).toBe(traceparent);
    expect(headers.has("tracestate")).toBe(false);
  });

  it("sends X-Request-ID and traceparent through the generated-client fetch adapter", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(null, { status: 204 }),
    );

    await publicFetch("/api/v1/products");

    const init = fetchMock.mock.calls[0]?.[1];
    const headers = new Headers(init?.headers);
    expect(headers.get("X-Request-ID")).toBeTruthy();
    expect(headers.get("traceparent")).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/u);
    fetchMock.mockRestore();
  });
});
