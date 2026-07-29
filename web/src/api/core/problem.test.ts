import { describe, expect, it } from "vitest";

import { mapProblemResponse } from "@/api/core/problem";

describe("Problem Details mapping", () => {
  it("maps RFC 9457 fields, violations, and correlation headers", async () => {
    const response = new Response(
      JSON.stringify({
        type: "https://hotshop.local/problems/validation-failed",
        title: "Validation failed",
        status: 400,
        detail: "One or more request fields are invalid",
        instance: "/api/v1/products",
        code: "VALIDATION_FAILED",
        requestId: "body-request",
        traceId: "body-trace",
        violations: [
          {
            field: "getProducts.limit",
            code: "Min",
            message: "must be greater than or equal to 1",
          },
        ],
      }),
      {
        status: 400,
        headers: {
          "Content-Type": "application/problem+json",
          "X-Request-Id": "header-request",
          "X-Trace-Id": "4bf92f3577b34da6a3ce929d0e0e4736",
        },
      },
    );

    const error = await mapProblemResponse(response);

    expect(error.problem).toMatchObject({
      code: "VALIDATION_FAILED",
      requestId: "header-request",
      traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
      status: 400,
    });
    expect(error.problem.violations).toEqual([
      {
        field: "getProducts.limit",
        code: "Min",
        message: "must be greater than or equal to 1",
      },
    ]);
  });

  it("creates a safe fallback for a non-Problem response", async () => {
    const response = new Response("<html>bad gateway</html>", { status: 502 });
    const error = await mapProblemResponse(response);

    expect(error.problem.code).toBe("UNEXPECTED_RESPONSE");
    expect(error.problem.detail).toContain("502");
  });
});
