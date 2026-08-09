import { apiClients } from "@/api/clients";
import type { FlashSaleActivityResponse } from "@/api/generated/public";

export interface FlashSaleActivity {
  activityId: string;
  activityCode: string;
  productId: string;
  productName: string;
  category?: string;
  description?: string;
  salePrice: string;
  availableStock: number;
  perUserLimit: number;
  status: string;
  phase: "UPCOMING" | "LIVE" | "SOLD_OUT" | "EXPIRED";
  startsAt: string;
  endsAt: string;
  serverTime: string;
}

function activity(value: FlashSaleActivityResponse): FlashSaleActivity {
  if (
    !value.activityId ||
    !value.activityCode ||
    !value.productId ||
    !value.productName ||
    !value.salePrice ||
    value.availableStock === undefined ||
    value.perUserLimit === undefined ||
    !value.status ||
    !value.phase ||
    !value.startsAt ||
    !value.endsAt ||
    !value.serverTime
  ) {
    throw new Error("活动响应不符合约定契约。");
  }
  return {
    activityId: value.activityId,
    activityCode: value.activityCode,
    productId: value.productId,
    productName: value.productName,
    category: value.category ?? undefined,
    description: value.description ?? undefined,
    salePrice: value.salePrice,
    availableStock: value.availableStock,
    perUserLimit: value.perUserLimit,
    status: value.status,
    phase: value.phase,
    startsAt: value.startsAt.toISOString(),
    endsAt: value.endsAt.toISOString(),
    serverTime: value.serverTime.toISOString(),
  };
}

export async function getActivities(): Promise<FlashSaleActivity[]> {
  return (await apiClients.public.activities.list({ limit: 12 })).map(activity);
}
