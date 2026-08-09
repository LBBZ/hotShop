import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { UserAuthPanel } from "@/features/auth/user-auth-panel";

describe("UserAuthPanel tabs", () => {
  it("links tabs to their panel and supports arrow, Home and End navigation", async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <UserAuthPanel />
      </MemoryRouter>,
    );

    const login = screen.getByRole("tab", { name: "登录" });
    const register = screen.getByRole("tab", { name: "注册" });
    const panel = screen.getByRole("tabpanel");
    expect(login).toHaveAttribute("aria-controls", "auth-panel");
    expect(register).toHaveAttribute("aria-controls", "auth-panel");
    expect(login).toHaveAttribute("tabindex", "0");
    expect(register).toHaveAttribute("tabindex", "-1");
    expect(panel).toHaveAttribute("aria-labelledby", "auth-tab-login");

    login.focus();
    await user.keyboard("{ArrowRight}");
    expect(register).toHaveFocus();
    expect(register).toHaveAttribute("aria-selected", "true");
    expect(panel).toHaveAttribute("aria-labelledby", "auth-tab-register");

    await user.keyboard("{Home}");
    expect(login).toHaveFocus();
    await user.keyboard("{End}");
    expect(register).toHaveFocus();
    await user.keyboard("{ArrowLeft}");
    expect(login).toHaveFocus();
  });
});
