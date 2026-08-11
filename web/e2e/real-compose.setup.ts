import { request } from "@playwright/test";

type ActivityLoadResponse = {
  activityId: number | string;
  result: "IDEMPOTENT" | "LOADED";
  consistent: boolean;
};

export default async function prepareRealCompose() {
  if (process.env.HOTSHOP_REAL_COMPOSE !== "1") {
    return;
  }
  if (process.env.HOTSHOP_E2E_ACTIVITIES_PRELOADED === "1") {
    return;
  }

  const adminBaseUrl =
    process.env.HOTSHOP_ADMIN_URL ?? "http://127.0.0.1:18088";
  const context = await request.newContext({ baseURL: adminBaseUrl });
  try {
    const login = await context.post("/admin/api/v1/auth/login", {
      data: {
        username: process.env.HOTSHOP_E2E_ADMIN_USERNAME ?? "task13-admin",
        password: process.env.HOTSHOP_E2E_ADMIN_PASSWORD ?? "Task13Admin!2026",
      },
    });
    if (!login.ok()) {
      throw new Error(`Admin login failed with HTTP ${login.status()}`);
    }
    const session = (await login.json()) as { accessToken: string };

    for (const activityId of [913001, 913002, 913003]) {
      const loaded = await context.post(
        `/admin/api/v1/flash-sales/${activityId}/load`,
        {
          data: { reason: "Prepare seeded activity for real Compose E2E" },
          headers: { Authorization: `Bearer ${session.accessToken}` },
        },
      );
      if (!loaded.ok()) {
        const problem = (await loaded.json()) as { code?: string };
        if (
          loaded.status() === 409 &&
          problem.code === "FLASH_SALE_RESERVATIONS_EXIST"
        ) {
          continue;
        }
        throw new Error(
          `Activity ${activityId} load failed with HTTP ${loaded.status()}: ${JSON.stringify(problem)}`,
        );
      }
      const result = (await loaded.json()) as ActivityLoadResponse;
      if (
        Number(result.activityId) !== activityId ||
        !["LOADED", "IDEMPOTENT"].includes(result.result) ||
        !result.consistent
      ) {
        throw new Error(
          `Activity ${activityId} load returned inconsistent facts: ${JSON.stringify(result)}`,
        );
      }
    }
  } finally {
    await context.dispose();
  }
}
