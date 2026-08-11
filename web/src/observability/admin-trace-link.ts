const TRACE_ID_PATTERN = /^[0-9a-f]{32}$/;
const ZERO_TRACE_ID = "00000000000000000000000000000000";

export type AdminTraceLinkBuilder = (
  traceId: string | null | undefined,
) => string | null;

function parseConfiguredBaseUrl(configuredBaseUrl: string | null | undefined) {
  if (!configuredBaseUrl) {
    return null;
  }

  try {
    const url = new URL(configuredBaseUrl);
    if (
      (url.protocol !== "http:" && url.protocol !== "https:") ||
      url.username !== "" ||
      url.password !== ""
    ) {
      return null;
    }
    return url;
  } catch {
    return null;
  }
}

export function isValidAdminTraceId(
  traceId: string | null | undefined,
): traceId is string {
  return (
    traceId !== null &&
    traceId !== undefined &&
    traceId !== ZERO_TRACE_ID &&
    TRACE_ID_PATTERN.test(traceId)
  );
}

/**
 * Creates a linker from the application's build-time Tempo/Grafana base URL.
 * The returned function accepts only a trace ID, so row data cannot replace the
 * configured destination with an arbitrary redirect URL.
 */
export function createAdminTraceLinkBuilder(
  configuredBaseUrl: string | null | undefined,
): AdminTraceLinkBuilder {
  const configuredUrl = parseConfiguredBaseUrl(configuredBaseUrl);

  return (traceId) => {
    if (!configuredUrl || !isValidAdminTraceId(traceId)) {
      return null;
    }

    const destination = new URL(configuredUrl);
    destination.searchParams.set("traceId", traceId);
    return destination.toString();
  };
}
