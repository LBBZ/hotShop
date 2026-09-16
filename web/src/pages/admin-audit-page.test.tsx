import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";

import { adminApi } from "@/features/admin/admin-api";
import { AdminAuditPage } from "@/pages/admin-audit-page";

afterEach(() => vi.restoreAllMocks());

it("preserves unknown audit codes and delegation, and resets pagination on exact filters", async () => {
  const load = vi.spyOn(adminApi, "auditLogs").mockResolvedValue({
    items: [
      {
        auditId: "42",
        action: "FUTURE_ACTION",
        resourceType: "FUTURE_RESOURCE",
        actorType: "AGENT",
        actorId: "agent",
        delegatedActorType: "USER",
        delegatedActorId: "71",
        result: "SUCCESS",
        requestId: "request",
        traceId: "",
        source: "AGENT_API",
        occurredAt: "2026-09-16T00:00:00Z",
        stateSummary: {},
      },
    ],
    hasMore: true,
    nextCursor: "next",
  });
  const user = userEvent.setup();
  render(<AdminAuditPage />);
  expect(await screen.findByText("FUTURE_ACTION")).toBeInTheDocument();
  expect(screen.getByText("FUTURE_RESOURCE")).toBeInTheDocument();
  expect(screen.getByText("委托：USER / 71")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: /下一页/ }));
  await waitFor(() =>
    expect(load).toHaveBeenLastCalledWith("next", "", "", ""),
  );
  await user.type(screen.getByLabelText("动作代码（精确）"), "FUTURE_ACTION");
  await user.type(screen.getByLabelText("资源类型（精确）"), "FUTURE_RESOURCE");
  await user.selectOptions(screen.getByLabelText("执行结果"), "DENIED");
  await waitFor(() =>
    expect(load).toHaveBeenLastCalledWith(
      undefined,
      "DENIED",
      "FUTURE_ACTION",
      "FUTURE_RESOURCE",
    ),
  );
});
