import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  clearPurchaseIntent,
  getOrCreatePurchaseIntent,
  purchaseIntentFingerprint,
} from "@/features/transactions/purchase-intent";

describe("purchase intent", () => {
  let randomUuid: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    sessionStorage.clear();
    randomUuid = vi
      .spyOn(crypto, "randomUUID")
      .mockReturnValueOnce("00000000-0000-4000-8000-000000000001")
      .mockReturnValueOnce("00000000-0000-4000-8000-000000000002");
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("reuses the key while the request fingerprint is unchanged", () => {
    const fingerprint = purchaseIntentFingerprint({
      productId: "12",
      quantity: 1,
    });
    const first = getOrCreatePurchaseIntent("intent", fingerprint, "order");
    const retry = getOrCreatePurchaseIntent("intent", fingerprint, "order");

    expect(retry).toEqual(first);
    expect(randomUuid).toHaveBeenCalledOnce();
  });

  it("starts a new intent when quantity changes", () => {
    const first = getOrCreatePurchaseIntent(
      "intent",
      purchaseIntentFingerprint({ productId: "12", quantity: 1 }),
      "order",
    );
    const changed = getOrCreatePurchaseIntent(
      "intent",
      purchaseIntentFingerprint({ productId: "12", quantity: 2 }),
      "order",
    );

    expect(changed.key).not.toBe(first.key);
    expect(changed.fingerprint).not.toBe(first.fingerprint);
  });

  it("only clears the matching completed intent", () => {
    const intent = getOrCreatePurchaseIntent("intent", "fingerprint", "order");
    clearPurchaseIntent("intent", "another-key");
    expect(sessionStorage.getItem("intent")).not.toBeNull();

    clearPurchaseIntent("intent", intent.key);
    expect(sessionStorage.getItem("intent")).toBeNull();
  });
});
