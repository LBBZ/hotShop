import { describe, expect, it } from "vitest";

import { getActivityAvailability } from "@/features/catalog/activity-availability";

describe("flash-sale activity availability", () => {
  it("distinguishes sold-out inventory from an expired time window", () => {
    const soldOut = getActivityAvailability("SOLD_OUT", false);
    const expired = getActivityAvailability("EXPIRED", false);

    expect(soldOut).toMatchObject({
      statusLabel: "已售罄",
      buttonLabel: "已售罄",
      disabled: true,
    });
    expect(soldOut.reason).toContain("库存");
    expect(expired).toMatchObject({
      statusLabel: "已过期",
      buttonLabel: "已过期",
      disabled: true,
    });
    expect(expired.reason).toContain("时间窗口");
  });

  it("treats a live activity whose calibrated countdown ended as expired", () => {
    expect(getActivityAvailability("LIVE", true)).toMatchObject({
      statusLabel: "已过期",
      buttonLabel: "已过期",
      disabled: true,
    });
  });
});
