function normalizeBaseUrl(value: string | undefined): string {
  if (!value || value === "/") {
    return "";
  }
  return value.replace(/\/+$/, "");
}

export const apiEnvironment = Object.freeze({
  baseUrl: normalizeBaseUrl(import.meta.env.VITE_API_BASE_URL),
});

export function toApiUrl(baseUrl: string, input: RequestInfo | URL): string {
  const rawUrl =
    typeof input === "string"
      ? input
      : input instanceof URL
        ? input.toString()
        : input.url;

  if (/^https?:\/\//u.test(rawUrl)) {
    return rawUrl;
  }

  const normalizedPath = rawUrl.startsWith("/") ? rawUrl : `/${rawUrl}`;
  return `${baseUrl}${normalizedPath}`;
}
