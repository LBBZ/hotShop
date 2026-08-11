import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiProblemError } from "@/api/core/problem";
import { adminApi } from "@/features/admin/admin-api";
import { AdminOutboxPage } from "@/pages/admin-outbox-page";

const event = {
  eventId: "01HZZZFAILED001",
  aggregateType: "SALES_ORDER",
  aggregateId: "order-913",
  eventType: "ORDER_CREATED",
  failureCategory: "BROKER_UNAVAILABLE",
  publishAttempts: 8,
  consecutiveAttempts: 3,
  manualReplayCount: 0,
  failedAt: "2026-08-09T12:00:00Z",
  createdAt: "2026-08-09T11:59:00Z",
};

describe("AdminOutboxPage", () => {
  afterEach(() => vi.restoreAllMocks());

  it("shows an honest empty state", async () => {
    vi.spyOn(adminApi, "failedOutbox").mockResolvedValue({
      items: [],
      hasMore: false,
    });
    render(<AdminOutboxPage />);
    expect(
      await screen.findByRole("heading", { name: "没有失败 Outbox" }),
    ).toBeInTheDocument();
  });

  it("requires a reason and a second confirmation before replay", async () => {
    vi.spyOn(adminApi, "failedOutbox").mockResolvedValue({
      items: [event],
      hasMore: false,
    });
    const replay = vi.spyOn(adminApi, "replayOutbox").mockResolvedValue();
    const user = userEvent.setup();
    render(<AdminOutboxPage />);

    await user.click(await screen.findByRole("button", { name: "重放" }));
    const confirm = screen.getByRole("button", {
      name: `确认重放 ${event.eventId}`,
    });
    expect(screen.getByRole("dialog")).toHaveTextContent(event.eventId);
    expect(screen.getByRole("dialog")).toHaveTextContent("可能再次向下游投递");
    expect(confirm).toBeDisabled();
    expect(replay).not.toHaveBeenCalled();

    await user.type(
      screen.getByLabelText("操作原因（必填）"),
      "运营确认消息代理已恢复",
    );
    expect(confirm).toBeEnabled();
    await user.click(confirm);
    await waitFor(() =>
      expect(replay).toHaveBeenCalledWith(
        event.eventId,
        "运营确认消息代理已恢复",
      ),
    );
    expect(await screen.findByRole("status")).toHaveTextContent(
      "已进入现有安全重放流程",
    );
  });

  it("renders the backend Problem Details when replay is rejected", async () => {
    vi.spyOn(adminApi, "failedOutbox").mockResolvedValue({
      items: [event],
      hasMore: false,
    });
    vi.spyOn(adminApi, "replayOutbox").mockRejectedValue(
      new ApiProblemError(
        {
          type: "https://hotshop.local/problems/conflict",
          title: "Conflict",
          status: 409,
          detail: "Only FAILED Outbox events can be replayed",
          instance: `/admin/api/v1/outbox/${event.eventId}/replay`,
          code: "OUTBOX_NOT_FAILED",
          requestId: "task14-replay-denied",
          traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
        },
        new Response(null, { status: 409 }),
      ),
    );
    const user = userEvent.setup();
    render(<AdminOutboxPage />);

    await user.click(await screen.findByRole("button", { name: "重放" }));
    await user.type(
      screen.getByLabelText("操作原因（必填）"),
      "重复操作拒绝验证",
    );
    await user.click(
      screen.getByRole("button", { name: `确认重放 ${event.eventId}` }),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "OUTBOX_NOT_FAILED",
    );
    expect(screen.getByRole("alert")).toHaveTextContent("task14-replay-denied");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });
});
