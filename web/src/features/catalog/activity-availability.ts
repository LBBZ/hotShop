import type { FlashSaleActivity } from "@/features/catalog/activity-api";

export function getActivityAvailability(
  phase: FlashSaleActivity["phase"],
  countdownExpired: boolean,
) {
  if (phase === "SOLD_OUT") {
    return {
      statusLabel: "已售罄",
      buttonLabel: "已售罄",
      disabled: true,
      reason: "活动库存已经售罄，当前不能预约。",
    };
  }
  if (phase === "EXPIRED" || (phase === "LIVE" && countdownExpired)) {
    return {
      statusLabel: "已过期",
      buttonLabel: "已过期",
      disabled: true,
      reason: "活动时间窗口已经结束，当前不能预约。",
    };
  }
  if (phase === "UPCOMING") {
    return {
      statusLabel: "即将开始",
      buttonLabel: "等待活动窗口",
      disabled: true,
      reason: "活动尚未开始，请等待活动窗口开放。",
    };
  }
  return {
    statusLabel: "正在进行",
    buttonLabel: "立即预约",
    disabled: false,
    reason: "活动正在进行，可以预约。",
  };
}
