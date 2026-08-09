import { userAuth } from "@/auth/domains";

export interface OrderFact {
  orderId: string;
  userId: string;
  totalAmount: string;
  currency: string;
  status: "PENDING" | "PAID" | "SHIPPED" | "COMPLETED" | "CANCELED";
  createdAt: string;
  items: Array<{
    productId: string;
    quantity: number;
    price: string;
    lineAmount: string;
  }>;
}

function isOrderFact(value: unknown): value is OrderFact {
  if (typeof value !== "object" || value === null) return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record.orderId === "string" &&
    typeof record.userId === "string" &&
    typeof record.totalAmount === "string" &&
    typeof record.currency === "string" &&
    typeof record.status === "string" &&
    typeof record.createdAt === "string" &&
    Array.isArray(record.items)
  );
}

export async function getOrder(orderId: string): Promise<OrderFact> {
  const response = await userAuth.fetch(
    `/api/v1/orders/${encodeURIComponent(orderId)}`,
  );
  const value: unknown = await response.json();
  if (!isOrderFact(value)) throw new Error("订单详情响应不符合约定契约。");
  return value;
}
