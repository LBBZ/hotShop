export interface PurchaseIntent {
  key: string;
  fingerprint: string;
}

function isPurchaseIntent(value: unknown): value is PurchaseIntent {
  if (typeof value !== "object" || value === null) return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record.key === "string" && typeof record.fingerprint === "string"
  );
}

export function purchaseIntentFingerprint(value: unknown): string {
  return JSON.stringify(value);
}

export function getOrCreatePurchaseIntent(
  storageKey: string,
  fingerprint: string,
  keyPrefix: string,
): PurchaseIntent {
  const stored = sessionStorage.getItem(storageKey);
  if (stored) {
    try {
      const value: unknown = JSON.parse(stored);
      if (isPurchaseIntent(value) && value.fingerprint === fingerprint) {
        return value;
      }
    } catch {
      // Legacy string values are intentionally replaced by the structured intent.
    }
  }

  const intent = { key: `${keyPrefix}:${crypto.randomUUID()}`, fingerprint };
  sessionStorage.setItem(storageKey, JSON.stringify(intent));
  return intent;
}

export function clearPurchaseIntent(storageKey: string, key: string): void {
  const stored = sessionStorage.getItem(storageKey);
  if (!stored) return;
  try {
    const value: unknown = JSON.parse(stored);
    if (isPurchaseIntent(value) && value.key === key) {
      sessionStorage.removeItem(storageKey);
    }
  } catch {
    sessionStorage.removeItem(storageKey);
  }
}
