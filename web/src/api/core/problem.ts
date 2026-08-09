export interface ApiViolation {
  field: string;
  code: string;
  message: string;
}

export interface ApiProblem {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance: string;
  code: string;
  requestId: string;
  traceId: string;
  violations?: ApiViolation[];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function stringField(
  record: Record<string, unknown>,
  key: string,
  fallback: string,
): string {
  const value = record[key];
  return typeof value === "string" ? value : fallback;
}

function numberField(
  record: Record<string, unknown>,
  key: string,
  fallback: number,
): number {
  const value = record[key];
  return typeof value === "number" ? value : fallback;
}

function parseViolations(value: unknown): ApiViolation[] | undefined {
  if (!Array.isArray(value)) {
    return undefined;
  }

  const violations = value.flatMap((candidate) => {
    if (!isRecord(candidate)) {
      return [];
    }
    return [
      {
        field: stringField(candidate, "field", "request"),
        code: stringField(candidate, "code", "INVALID"),
        message: stringField(candidate, "message", "The value is invalid"),
      },
    ];
  });
  return violations.length > 0 ? violations : undefined;
}

export class ApiProblemError extends Error {
  readonly problem: ApiProblem;
  readonly response: Response;

  constructor(problem: ApiProblem, response: Response) {
    super(problem.detail);
    this.name = "ApiProblemError";
    this.problem = problem;
    this.response = response;
  }
}

export function findApiProblemError(
  error: unknown,
): ApiProblemError | undefined {
  let current = error;
  const visited = new Set<unknown>();
  for (let depth = 0; depth < 4; depth += 1) {
    if (current instanceof ApiProblemError) return current;
    if (!(current instanceof Error) || visited.has(current)) return undefined;
    visited.add(current);
    current = current.cause;
  }
  return undefined;
}

export async function mapProblemResponse(
  response: Response,
): Promise<ApiProblemError> {
  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    payload = undefined;
  }

  const record = isRecord(payload) ? payload : {};
  const requestId =
    response.headers.get("X-Request-Id") ??
    stringField(record, "requestId", "unavailable");
  const traceId =
    response.headers.get("X-Trace-Id") ??
    stringField(record, "traceId", "unavailable");
  const responsePath = (() => {
    try {
      return response.url ? new URL(response.url).pathname : "/";
    } catch {
      return "/";
    }
  })();

  const violations = parseViolations(record.violations);
  const problem: ApiProblem = {
    type: stringField(
      record,
      "type",
      "https://hotshop.local/problems/unexpected-response",
    ),
    title: stringField(record, "title", "Request failed"),
    status: numberField(record, "status", response.status),
    detail: stringField(
      record,
      "detail",
      `The server returned HTTP ${response.status}.`,
    ),
    instance: stringField(record, "instance", responsePath),
    code: stringField(record, "code", "UNEXPECTED_RESPONSE"),
    requestId,
    traceId,
    ...(violations ? { violations } : {}),
  };

  return new ApiProblemError(problem, response);
}
