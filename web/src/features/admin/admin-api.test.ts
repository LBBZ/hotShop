import { afterEach, describe, expect, it, vi } from "vitest";

import { adminAuth } from "@/auth/domains";
import { adminApi } from "@/features/admin/admin-api";

describe("Administrator operation network deadlines", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it.each([
    ["load", () => adminApi.loadActivity("901", "核对活动库存")],
    [
      "replay",
      () =>
        adminApi.replayOutbox(
          "14141414-1414-4141-8141-141414141414",
          "核对消息状态",
        ),
    ],
  ] as const)(
    "ends a stalled %s request without issuing another mutation",
    async (_name, operate) => {
      vi.useFakeTimers();
      // Use controlled time while keeping real AbortSignal delivery to the fetch boundary.
      vi.spyOn(AbortSignal, "timeout").mockImplementation((delay) => {
        const controller = new AbortController();
        setTimeout(
          () =>
            controller.abort(
              new DOMException("Deadline reached", "TimeoutError"),
            ),
          delay,
        );
        return controller.signal;
      });
      const fetch = vi.spyOn(adminAuth, "fetch").mockImplementation(
        (_input, init) =>
          new Promise((_resolve, reject) => {
            init?.signal?.addEventListener(
              "abort",
              () => reject(init.signal?.reason as Error),
              { once: true },
            );
          }),
      );
      let outcome: unknown;
      void operate().then(
        () => {
          outcome = "completed";
        },
        (error: unknown) => {
          outcome = error;
        },
      );
      await vi.advanceTimersByTimeAsync(15_000);
      expect(outcome).toBeInstanceOf(DOMException);
      expect((outcome as DOMException).name).toBe("TimeoutError");
      expect(fetch).toHaveBeenCalledTimes(1);
    },
  );
});
