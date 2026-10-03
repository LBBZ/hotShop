import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  adminApi,
  type AdminPayment,
  type CursorPage,
  type ManualReview,
  type ReconciliationIssue,
} from "@/features/admin/admin-api";
import { AdminExceptionsPage } from "@/pages/admin-exceptions-page";

const time = "2026-10-03T10:00:00Z";
const issue = (id: string): ReconciliationIssue => ({
  issueId: id,
  issueType: `conflict-${id}`,
  severity: "CRITICAL",
  status: "OPEN",
  occurrences: 1,
  evidenceVersion: 1,
  evidenceSummary: { equationHolds: false },
  firstSeenAt: time,
  lastSeenAt: time,
});
const review = (id: string): ManualReview => ({
  processingId: id,
  eventId: `review-${id}`,
  status: "MANUAL_REVIEW",
  attempts: 3,
  reasonCode: "FACT_CONFLICT",
  updatedAt: time,
});
const payment = (id: string): AdminPayment => ({
  paymentId: id,
  paymentNo: `payment-${id}`,
  orderId: `order-${id}`,
  provider: "MOCK",
  amount: "19.90",
  currency: "CNY",
  status: "PENDING",
  createdAt: time,
  updatedAt: time,
});
const page = <T,>(item: T, nextCursor?: string): CursorPage<T> => ({
  items: [item],
  hasMore: !!nextCursor,
  nextCursor,
});
const region = (name: string) => within(screen.getByRole("region", { name }));

function mockFacts() {
  return {
    status: vi.spyOn(adminApi, "reconciliationStatus").mockResolvedValue({
      dryRun: null,
      autoRepair: null,
      openIssues: 25,
      criticalOpenIssues: 2,
      factStatement: "Only persisted findings are available.",
    }),
    issues: vi
      .spyOn(adminApi, "reconciliationIssues")
      .mockImplementation((cursor) =>
        Promise.resolve(
          cursor ? page(issue("21")) : page(issue("1"), "issues-next"),
        ),
      ),
    reviews: vi
      .spyOn(adminApi, "manualReviews")
      .mockImplementation((cursor) =>
        Promise.resolve(
          cursor ? page(review("21")) : page(review("1"), "reviews-next"),
        ),
      ),
    payments: vi
      .spyOn(adminApi, "payments")
      .mockImplementation((_range, cursor) =>
        Promise.resolve(
          cursor ? page(payment("21")) : page(payment("1"), "payments-next"),
        ),
      ),
  };
}

describe("AdminExceptionsPage investigation workflow", () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date(time));
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("paginates all three lists independently and can return to the previous page", async () => {
    const facts = mockFacts();
    render(<AdminExceptionsPage />);
    await screen.findByText("payment-1");
    const originalRange = facts.payments.mock.calls[0]?.[0];

    fireEvent.click(region("对账异常").getByRole("button", { name: "下一页" }));
    await screen.findByText("conflict-21");
    expect(facts.issues).toHaveBeenLastCalledWith("issues-next", "OPEN");
    expect(facts.reviews).toHaveBeenCalledTimes(1);
    expect(facts.payments).toHaveBeenCalledTimes(1);
    fireEvent.click(region("对账异常").getByRole("button", { name: "上一页" }));
    await screen.findByText("conflict-1");

    fireEvent.click(
      region("待人工处理").getByRole("button", { name: "下一页" }),
    );
    await screen.findByText("review-21");
    expect(facts.reviews).toHaveBeenLastCalledWith("reviews-next");
    vi.setSystemTime(new Date("2026-10-03T10:15:00Z"));
    fireEvent.click(region("支付状态").getByRole("button", { name: "下一页" }));
    await screen.findByText("payment-21");
    expect(facts.payments).toHaveBeenLastCalledWith(
      originalRange,
      "payments-next",
      "",
    );
  });

  it("keeps other facts visible when payments fail and retries only that panel", async () => {
    const facts = mockFacts();
    facts.payments.mockRejectedValueOnce(new Error("支付查询暂时不可用"));
    render(<AdminExceptionsPage />);
    await screen.findByText("支付查询暂时不可用");
    expect(screen.getByText("conflict-1")).toBeInTheDocument();
    expect(screen.getByText("review-1")).toBeInTheDocument();
    expect(
      screen.getByText("Only persisted findings are available."),
    ).toBeInTheDocument();
    fireEvent.click(
      region("支付状态").getByRole("button", { name: "重新同步" }),
    );
    await screen.findByText("payment-1");
    expect(facts.status).toHaveBeenCalledTimes(1);
    expect(facts.issues).toHaveBeenCalledTimes(1);
    expect(facts.reviews).toHaveBeenCalledTimes(1);
  });

  it("keeps investigation lists available when the status summary fails", async () => {
    const facts = mockFacts();
    facts.status.mockRejectedValueOnce(new Error("运行摘要暂时不可用"));
    render(<AdminExceptionsPage />);
    await screen.findByText("运行摘要暂时不可用");
    expect(screen.getByText("conflict-1")).toBeInTheDocument();
    expect(screen.getByText("review-1")).toBeInTheDocument();
    expect(screen.getByText("payment-1")).toBeInTheDocument();
  });

  it("resets cursors when filters change, and refresh advances the payment window", async () => {
    const facts = mockFacts();
    render(<AdminExceptionsPage />);
    await screen.findByText("payment-1");
    fireEvent.click(region("支付状态").getByRole("button", { name: "下一页" }));
    await screen.findByText("payment-21");
    fireEvent.change(screen.getByLabelText("支付结果"), {
      target: { value: "LATE_SUCCEEDED" },
    });
    await screen.findByText("payment-1");
    expect(facts.payments.mock.lastCall?.slice(1)).toEqual([
      undefined,
      "LATE_SUCCEEDED",
    ]);
    fireEvent.change(screen.getByLabelText("支付时间范围"), {
      target: { value: "168" },
    });
    await waitFor(() =>
      expect(facts.payments.mock.lastCall?.[0].createdFrom).toEqual(
        new Date("2026-09-26T10:00:00Z"),
      ),
    );

    fireEvent.click(region("对账异常").getByRole("button", { name: "下一页" }));
    await screen.findByText("conflict-21");
    fireEvent.change(screen.getByLabelText("异常状态"), {
      target: { value: "RESOLVED" },
    });
    await screen.findByText("conflict-1");
    expect(facts.issues).toHaveBeenLastCalledWith(undefined, "RESOLVED");
    fireEvent.click(
      region("待人工处理").getByRole("button", { name: "下一页" }),
    );
    await screen.findByText("review-21");

    vi.setSystemTime(new Date("2026-10-03T11:00:00Z"));
    fireEvent.click(screen.getByRole("button", { name: "刷新全部" }));
    await screen.findByText("review-1");
    await waitFor(() =>
      expect(facts.payments.mock.lastCall?.[0].createdTo).toEqual(
        new Date("2026-10-03T11:00:00Z"),
      ),
    );
    expect(facts.payments.mock.lastCall?.[0].createdFrom).toEqual(
      new Date("2026-09-26T11:00:00Z"),
    );
    expect(facts.payments.mock.lastCall?.slice(1)).toEqual([
      undefined,
      "LATE_SUCCEEDED",
    ]);
    expect(facts.issues).toHaveBeenLastCalledWith(undefined, "RESOLVED");
  });

  it("can retry a failed next page without mixing the other lists or losing the cursor", async () => {
    const facts = mockFacts();
    facts.issues
      .mockResolvedValueOnce(page(issue("1"), "issues-next"))
      .mockRejectedValueOnce(new Error("下一页读取失败"));
    render(<AdminExceptionsPage />);
    await screen.findByText("conflict-1");
    fireEvent.click(region("对账异常").getByRole("button", { name: "下一页" }));
    await screen.findByText("下一页读取失败");
    expect(
      region("对账异常").getByRole("button", { name: "上一页" }),
    ).toBeEnabled();
    expect(screen.getByText("review-1")).toBeInTheDocument();
    fireEvent.click(
      region("对账异常").getByRole("button", { name: "重新同步" }),
    );
    await screen.findByText("conflict-21");
    expect(facts.issues).toHaveBeenLastCalledWith("issues-next", "OPEN");
    expect(facts.payments).toHaveBeenCalledTimes(1);
  });
});
