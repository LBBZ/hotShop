import { apiClients } from "@/api/clients";
import { adminAuth } from "@/auth/domains";

export interface CursorPage<T> {
  hasMore: boolean;
  items: T[];
  nextCursor?: string | null;
}

export interface AdminQueryTimeRange {
  createdFrom: Date;
  createdTo: Date;
}

export function createAdminQueryTimeRange(
  windowHours = 24,
  now = new Date(),
): AdminQueryTimeRange {
  return {
    createdFrom: new Date(now.valueOf() - windowHours * 60 * 60 * 1000),
    createdTo: now,
  };
}

export interface OperationsOverview {
  rangeFrom: string;
  rangeTo: string;
  generatedAt: string;
  source: string;
  productsCreated: number;
  activitiesCreated: number;
  ordersCreated: number;
  reservationsCreated: number;
  paymentsCreated: number;
  failedOutboxUpdated: number;
  openReconciliationIssues: number;
  pendingManualReviews: number;
}

export interface AdminProduct {
  productId: string;
  name: string;
  price: string;
  stock: number;
  category: string;
  description?: string;
  createdAt: string | Date;
}

export interface ProductMutation {
  name: string;
  price: string;
  stock: number;
  category: string;
  description?: string;
  reason: string;
}

export interface AdminActivity {
  activityId: string;
  activityCode: string;
  productId: string;
  salePrice: string;
  totalStock: number;
  availableStock: number;
  perUserLimit: number;
  status: string;
  startsAt: string;
  endsAt: string;
  version: number;
  updatedAt: string;
}

export interface AdminOrder {
  orderId: string;
  userId: string;
  status: string;
  totalAmount: string;
  currency: string;
  createdAt: string | Date;
  items: unknown[];
}

export interface AdminPayment {
  paymentId: string;
  paymentNo: string;
  orderId: string;
  provider: string;
  amount: string;
  currency: string;
  status: string;
  expiresAt?: string;
  paidAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface ReconciliationIssue {
  issueId: string;
  issueType: string;
  severity: string;
  status: string;
  activityId?: string;
  reservationNo?: string;
  occurrences: number;
  evidenceVersion: number;
  evidenceSummary: Record<string, unknown>;
  firstSeenAt: string;
  lastSeenAt: string;
  traceId?: string;
}

export interface ManualReview {
  processingId: string;
  eventId: string;
  reservationNo?: string;
  activityId?: string;
  status: string;
  attempts: number;
  reasonCode: string;
  lastError?: string;
  updatedAt: string;
  traceId?: string;
}

export interface ReconciliationStatus {
  dryRun: boolean | null;
  autoRepair: boolean | null;
  lastCheckpointAt?: string;
  openIssues: number;
  criticalOpenIssues: number;
  factStatement: string;
}

export interface FailedOutboxEvent {
  eventId: string;
  aggregateType: string;
  aggregateId: string;
  eventType: string;
  failureCategory: string;
  publishAttempts: number;
  consecutiveAttempts: number;
  manualReplayCount: number;
  failedAt: string | Date;
  createdAt: string | Date;
}

export interface AuditEntry {
  auditId?: string;
  action: string;
  actorId?: string | null;
  actorType: string;
  occurredAt: string | Date;
  requestId: string;
  resourceId?: string | null;
  resourceType: string;
  result: string;
  source: string;
  stateSummary: Record<string, unknown>;
  traceId: string;
}

function queryString(values: Record<string, string | number | undefined>) {
  const query = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value !== undefined && value !== "") query.set(key, String(value));
  });
  const encoded = query.toString();
  return encoded ? `?${encoded}` : "";
}

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await adminAuth.fetch(path, init);
  return (await response.json()) as T;
}

async function voidRequest(path: string, init: RequestInit): Promise<void> {
  await adminAuth.fetch(path, init);
}

const jsonHeaders = { "Content-Type": "application/json" };

export const adminApi = {
  overview: (windowHours = 24) =>
    json<OperationsOverview>(
      `/admin/api/v1/operations/overview${queryString({ windowHours })}`,
    ),
  products: async (cursor?: string, keyword?: string) => {
    const result = await apiClients.admin.products.searchProducts({
      limit: 20,
      cursor,
      keyword,
    });
    return result as unknown as CursorPage<AdminProduct>;
  },
  createProduct: (value: ProductMutation) =>
    json<AdminProduct>("/admin/api/v1/products", {
      method: "POST",
      headers: jsonHeaders,
      body: JSON.stringify(value),
    }),
  updateProduct: (productId: string, value: ProductMutation) =>
    json<AdminProduct>(
      `/admin/api/v1/products/${encodeURIComponent(productId)}`,
      {
        method: "PUT",
        headers: jsonHeaders,
        body: JSON.stringify(value),
      },
    ),
  deleteProduct: (productId: string, reason: string) =>
    voidRequest(`/admin/api/v1/products/${encodeURIComponent(productId)}`, {
      method: "DELETE",
      headers: jsonHeaders,
      body: JSON.stringify({ reason }),
    }),
  activities: (cursor?: string, status?: string, productId?: string) =>
    json<CursorPage<AdminActivity>>(
      `/admin/api/v1/flash-sales${queryString({ limit: 20, cursor, status, productId })}`,
    ),
  loadActivity: (activityId: string, reason: string) =>
    json<Record<string, unknown>>(
      `/admin/api/v1/flash-sales/${encodeURIComponent(activityId)}/load`,
      {
        method: "POST",
        headers: jsonHeaders,
        body: JSON.stringify({ reason }),
      },
    ),
  orders: async (
    timeRange: AdminQueryTimeRange,
    cursor?: string,
    status?: string,
  ) => {
    const result = await apiClients.admin.orders.getOrders({
      limit: 20,
      cursor,
      status: status as never,
      createdFrom: timeRange.createdFrom,
      createdTo: timeRange.createdTo,
    });
    return result as unknown as CursorPage<AdminOrder>;
  },
  payments: (
    timeRange: AdminQueryTimeRange,
    cursor?: string,
    status?: string,
  ) =>
    json<CursorPage<AdminPayment>>(
      `/admin/api/v1/operations/payments${queryString({
        limit: 20,
        cursor,
        status,
        createdFrom: timeRange.createdFrom.toISOString(),
        createdTo: timeRange.createdTo.toISOString(),
      })}`,
    ),
  reconciliationIssues: (cursor?: string, status?: string) =>
    json<CursorPage<ReconciliationIssue>>(
      `/admin/api/v1/operations/reconciliation-issues${queryString({ limit: 20, cursor, status })}`,
    ),
  manualReviews: (cursor?: string, status?: string) =>
    json<CursorPage<ManualReview>>(
      `/admin/api/v1/operations/manual-reviews${queryString({ limit: 20, cursor, status })}`,
    ),
  reconciliationStatus: () =>
    json<ReconciliationStatus>(
      "/admin/api/v1/operations/reconciliation-status",
    ),
  failedOutbox: (cursor?: string) =>
    json<CursorPage<FailedOutboxEvent>>(
      `/admin/api/v1/outbox/failed${queryString({ limit: 20, cursor })}`,
    ),
  replayOutbox: (eventId: string, reason: string) =>
    voidRequest(`/admin/api/v1/outbox/${encodeURIComponent(eventId)}/replay`, {
      method: "POST",
      headers: jsonHeaders,
      body: JSON.stringify({ reason }),
    }),
  auditLogs: (cursor?: string, result?: string) =>
    json<CursorPage<AuditEntry>>(
      `/admin/api/v1/audit-logs${queryString({ limit: 20, cursor, result })}`,
    ),
};
