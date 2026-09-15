import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiProblemError } from "@/api/core/problem";
import { adminApi } from "@/features/admin/admin-api";
import { AdminProductsPage } from "@/pages/admin-products-page";

const product = {
  productId: "71",
  name: "原商品",
  price: "10.00",
  stock: 100,
  version: "2147483644",
  category: "测试",
  description: "回归商品",
  createdAt: "2026-09-15T00:00:00Z",
};

describe("AdminProductsPage inventory boundaries", () => {
  afterEach(() => vi.restoreAllMocks());

  it("a stale name-only form never sends its cached stock", async () => {
    let currentStock = 100;
    vi.spyOn(adminApi, "products").mockImplementation(() =>
      Promise.resolve({
        items: [{ ...product, stock: currentStock }],
        hasMore: false,
      }),
    );
    const update = vi
      .spyOn(adminApi, "updateProduct")
      .mockImplementation((_id, value) => {
        if ("stock" in value) currentStock = Number(value.stock);
        return Promise.resolve({ ...product, ...value, stock: currentStock });
      });
    const user = userEvent.setup();
    render(<AdminProductsPage />);
    await user.click(await screen.findByRole("button", { name: "编辑" }));
    currentStock = 99; // Another request commits after this editor opened.
    await user.clear(screen.getByLabelText("商品名称"));
    await user.type(screen.getByLabelText("商品名称"), "新商品名称");
    await user.type(screen.getByLabelText(/变更原因/), "只修改商品名称");
    await user.click(screen.getByRole("button", { name: "提交并记录审计" }));
    await waitFor(() => expect(update).toHaveBeenCalledOnce());
    expect(update.mock.calls[0]?.[1]).not.toHaveProperty("stock");
    expect(currentStock).toBe(99);
    expect(await screen.findByRole("cell", { name: "99" })).toBeInTheDocument();
  });

  it("sends an explicit delta with the exact version and requires refresh after a conflict", async () => {
    vi.spyOn(adminApi, "products").mockResolvedValue({
      items: [product],
      hasMore: false,
    });
    const current = { ...product, stock: 99, version: "2147483645" };
    const refresh = vi.spyOn(adminApi, "product").mockResolvedValue(current);
    const adjust = vi
      .spyOn(adminApi, "adjustStock")
      .mockRejectedValueOnce(
        new ApiProblemError(
          {
            type: "about:blank",
            title: "Conflict",
            status: 409,
            detail: "Stock changed",
            instance: "/admin/api/v1/products/71/stock-adjustments",
            code: "STOCK_ADJUSTMENT_CONFLICT",
            requestId: "review-stock-conflict",
            traceId: "trace",
          },
          new Response(null, { status: 409 }),
        ),
      )
      .mockResolvedValueOnce({
        ...current,
        stock: 104,
        version: "2147483646",
      });
    const user = userEvent.setup();
    render(<AdminProductsPage />);
    await user.click(await screen.findByRole("button", { name: "调整库存" }));
    await user.type(screen.getByLabelText("调整数量"), "5");
    await user.type(screen.getByLabelText("调整原因"), "实际补货五件");
    await user.click(screen.getByRole("button", { name: "确认调整库存" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "本次调整未生效",
    );
    expect(screen.getByRole("alert")).toHaveTextContent(
      "review-stock-conflict",
    );
    expect(adjust).toHaveBeenNthCalledWith(1, "71", {
      delta: 5,
      expectedVersion: "2147483644",
      reason: "实际补货五件",
    });
    expect(screen.getByRole("button", { name: "确认调整库存" })).toBeDisabled();
    expect(adjust).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole("button", { name: "刷新当前库存" }));
    await waitFor(() => expect(refresh).toHaveBeenCalledWith("71"));
    expect(screen.getByRole("status")).toHaveTextContent("调整后库存：104");
    await user.click(screen.getByRole("button", { name: "确认调整库存" }));
    await waitFor(() =>
      expect(adjust).toHaveBeenNthCalledWith(2, "71", {
        delta: 5,
        expectedVersion: "2147483645",
        reason: "实际补货五件",
      }),
    );
  });

  it("rejects zero and reductions below zero before sending adjustments", async () => {
    vi.spyOn(adminApi, "products").mockResolvedValue({
      items: [product],
      hasMore: false,
    });
    const adjust = vi.spyOn(adminApi, "adjustStock");
    const user = userEvent.setup();
    render(<AdminProductsPage />);
    await user.click(await screen.findByRole("button", { name: "调整库存" }));
    await user.type(screen.getByLabelText("调整原因"), "实际库存盘点");
    await user.type(screen.getByLabelText("调整数量"), "0");
    expect(screen.getByRole("button", { name: "确认调整库存" })).toBeDisabled();
    await user.clear(screen.getByLabelText("调整数量"));
    await user.type(screen.getByLabelText("调整数量"), "-101");
    expect(screen.getByRole("button", { name: "确认调整库存" })).toBeDisabled();
    expect(adjust).not.toHaveBeenCalled();
  });
});
