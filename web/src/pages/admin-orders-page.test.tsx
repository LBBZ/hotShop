import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { apiClients } from "@/api/clients";
import { AdminOrdersPage } from "@/pages/admin-orders-page";

function order(orderId: string) {
  return {
    orderId,
    userId: "913001",
    status: "PENDING",
    totalAmount: "99.00",
    currency: "CNY",
    createdAt: "2026-08-10T02:00:00Z",
    items: [],
  };
}

describe("AdminOrdersPage", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("reuses one time range across cursor pages and resets it with filters", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-08-10T02:00:00Z"));
    const getOrders = vi
      .spyOn(apiClients.admin.orders, "getOrders")
      .mockResolvedValueOnce({
        items: [order("task14-page-01")],
        hasMore: true,
        nextCursor: "signed-next-cursor",
      } as never)
      .mockResolvedValueOnce({
        items: [order("task14-page-21")],
        hasMore: false,
      } as never)
      .mockResolvedValueOnce({
        items: [order("task14-paid-01")],
        hasMore: false,
      } as never);

    render(<AdminOrdersPage />);
    await screen.findByText("task14-page-01");
    expect(getOrders).toHaveBeenCalledTimes(1);

    vi.setSystemTime(new Date("2026-08-10T02:15:00Z"));
    fireEvent.click(screen.getByRole("button", { name: "加载下一页" }));
    await screen.findByText("task14-page-21");
    expect(getOrders).toHaveBeenCalledTimes(2);

    const firstRequest = getOrders.mock.calls[0]?.[0];
    const secondRequest = getOrders.mock.calls[1]?.[0];
    expect(secondRequest?.cursor).toBe("signed-next-cursor");
    expect(secondRequest?.createdFrom).toEqual(firstRequest?.createdFrom);
    expect(secondRequest?.createdTo).toEqual(firstRequest?.createdTo);

    vi.setSystemTime(new Date("2026-08-10T02:30:00Z"));
    fireEvent.change(screen.getByLabelText("订单状态"), {
      target: { value: "PAID" },
    });
    await screen.findByText("task14-paid-01");
    await waitFor(() => expect(getOrders).toHaveBeenCalledTimes(3));

    const filteredRequest = getOrders.mock.calls[2]?.[0];
    expect(filteredRequest?.cursor).toBeUndefined();
    expect(filteredRequest?.status).toBe("PAID");
    expect(filteredRequest?.createdTo).toEqual(
      new Date("2026-08-10T02:30:00Z"),
    );
    expect(filteredRequest?.createdFrom).toEqual(
      new Date("2026-08-09T02:30:00Z"),
    );
  });
});
