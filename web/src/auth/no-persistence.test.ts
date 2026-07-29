import { describe, expect, it, vi } from "vitest";

import { createAuthStore } from "@/auth/auth-store";

describe("Access Token persistence prohibition", () => {
  it("keeps Access Token state in memory without touching browser persistence", () => {
    const storageWrite = vi.spyOn(Storage.prototype, "setItem");
    const cookieWrite = vi.spyOn(Document.prototype, "cookie", "set");
    const indexedDbOpen = vi.fn();
    Object.defineProperty(window, "indexedDB", {
      configurable: true,
      value: { open: indexedDbOpen },
    });

    const store = createAuthStore();
    store.getState().setSession({
      accessToken: "memory-only-secret",
      expiresAt: "2030-01-01T00:00:00Z",
      role: "ROLE_USER",
      userId: "101",
      username: "lin",
    });

    expect(store.getState().session?.accessToken).toBe("memory-only-secret");
    expect(storageWrite).not.toHaveBeenCalled();
    expect(cookieWrite).not.toHaveBeenCalled();
    expect(indexedDbOpen).not.toHaveBeenCalled();
    expect(localStorage.getItem("accessToken")).toBeNull();
    expect(sessionStorage.getItem("accessToken")).toBeNull();
    expect(document.cookie).not.toContain("memory-only-secret");
  });
});
