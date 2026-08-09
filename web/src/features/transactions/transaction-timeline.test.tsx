import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { initialTransactionState } from "@/features/transactions/status-machine";
import { TransactionTimeline } from "@/features/transactions/transaction-timeline";

describe("TransactionTimeline connection announcements", () => {
  it.each([
    ["live", "实时连接"],
    ["reconnecting", "正在重连"],
    ["offline", "离线等待"],
  ] as const)("announces the %s state", (connection, label) => {
    render(
      <TransactionTimeline
        state={initialTransactionState}
        connection={connection}
      />,
    );

    const status = screen.getByRole("status");
    expect(status).toHaveTextContent(label);
    expect(status).toHaveAttribute("aria-live", "polite");
    expect(status).toHaveAttribute("aria-atomic", "true");
  });
});
