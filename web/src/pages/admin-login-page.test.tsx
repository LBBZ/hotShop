import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";

import { apiClients } from "@/api/clients";
import { adminAuth } from "@/auth/domains";
import { AdminLoginPage } from "@/pages/admin-login-page";

describe("AdminLoginPage", () => {
  afterEach(() => {
    adminAuth.store.getState().clearSession();
    vi.restoreAllMocks();
  });

  it("rejects a non-Administrator login response and stores no token", async () => {
    vi.spyOn(apiClients.admin.authentication, "login").mockResolvedValue({
      accessToken: "user-token",
      expiresAt: new Date("2030-01-01T00:00:00Z"),
      role: "ROLE_USER",
      userId: "101",
      username: "ordinary-user",
    });
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <AdminLoginPage />
      </MemoryRouter>,
    );

    await user.type(screen.getByLabelText("管理员用户名"), "ordinary-user");
    await user.type(screen.getByLabelText("密码"), "Password!2026");
    await user.click(
      screen.getByRole("button", { name: "以 Administrator 身份登录" }),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "不是 Administrator",
    );
    expect(adminAuth.store.getState().session).toBeNull();
  });
});
