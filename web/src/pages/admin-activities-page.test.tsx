import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiProblemError } from "@/api/core/problem";
import { adminApi } from "@/features/admin/admin-api";
import { AdminActivitiesPage } from "@/pages/admin-activities-page";

const activity = {
  activityId: "901",
  activityCode: "AUDIT-901",
  productId: "801",
  salePrice: "19.90",
  totalStock: 20,
  availableStock: 20,
  perUserLimit: 1,
  status: "ACTIVE",
  startsAt: "2026-10-03T08:00:00Z",
  endsAt: "2026-10-03T20:00:00Z",
  version: 0,
  updatedAt: "2026-10-03T08:00:00Z",
};
const loadResult = {
  activityId: "901",
  result: "IDEMPOTENT",
  databaseVersion: 0,
  redisVersion: 0,
  databaseAvailableStock: 20,
  redisAvailableStock: 19,
  streamEventCount: 0,
  reservationRecordCount: 0,
  reservedQuantity: 0,
  consistent: false,
  detail: "The same database activity version is already loaded",
};
async function confirmLoad() {
  vi.spyOn(adminApi, "activities").mockResolvedValue({
    items: [
      activity,
      { ...activity, activityId: "902", activityCode: "AUDIT-902" },
    ],
    hasMore: false,
  });
  const user = userEvent.setup();
  render(<AdminActivitiesPage />);
  await user.click(
    (await screen.findAllByRole("button", { name: "加载并核验" }))[0]!,
  );
  await user.type(screen.getByLabelText("操作原因"), "核对活动库存");
  await user.click(screen.getByRole("button", { name: "确认加载" }));
  return user;
}

describe("AdminActivitiesPage load outcome", () => {
  afterEach(() => vi.restoreAllMocks());

  it("warns about inconsistent facts even when loading returned HTTP success", async () => {
    vi.spyOn(adminApi, "loadActivity").mockResolvedValue(loadResult);
    await confirmLoad();
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("库存核验未确认一致");
    expect(alert).toHaveTextContent("IDEMPOTENT");
    expect(alert).toHaveTextContent("MySQL 可用库存：20");
    expect(alert).toHaveTextContent("Redis 可用库存：19");
    expect(
      within(alert).getByRole("link", { name: "查看异常与人工处理" }),
    ).toHaveAttribute("href", "/admin/exceptions");
  });

  it("presents backend rejection as an error instead of a green completion notice", async () => {
    vi.spyOn(adminApi, "loadActivity").mockRejectedValue(
      new ApiProblemError(
        {
          type: "about:blank",
          title: "Conflict",
          status: 409,
          detail: "已有预约不能重装",
          instance: "/admin/api/v1/flash-sales/901/load",
          code: "FLASH_SALE_RESERVATIONS_EXIST",
          requestId: "audit-load-conflict",
          traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
        },
        new Response(null, { status: 409 }),
      ),
    );
    await confirmLoad();
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("FLASH_SALE_RESERVATIONS_EXIST");
    expect(alert).toHaveTextContent("audit-load-conflict");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("keeps the submitted activity fixed while waiting for the server", async () => {
    let finish!: (value: typeof loadResult) => void;
    vi.spyOn(adminApi, "loadActivity").mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    await confirmLoad();
    try {
      expect(screen.getByRole("button", { name: "取消" })).toBeDisabled();
      expect(screen.getByLabelText("操作原因")).toBeDisabled();
      for (const button of screen.getAllByRole("button", {
        name: "加载并核验",
      }))
        expect(button).toBeDisabled();
    } finally {
      finish({ ...loadResult, consistent: true });
    }
    expect(await screen.findByRole("status")).toHaveTextContent(
      "本次库存核验一致",
    );
  });

  it("treats a lost response as unconfirmed and asks for audit verification", async () => {
    vi.spyOn(adminApi, "loadActivity").mockRejectedValue(
      new DOMException("Deadline reached", "TimeoutError"),
    );
    await confirmLoad();
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("尚未确认");
    expect(alert).toHaveTextContent("审计记录");
    expect(screen.getByRole("button", { name: "取消" })).toBeEnabled();
    expect(screen.getByRole("dialog")).toHaveTextContent("901");
  });
});
