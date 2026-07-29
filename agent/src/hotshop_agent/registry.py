from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ToolRegistry:
    boundary: str
    tools: tuple[str, ...] = ()

    def resolve(self, name: str) -> None:
        raise LookupError(f"No tools are registered for the {self.boundary} boundary")


USER_TOOLS = ToolRegistry(boundary="user")
ADMIN_TOOLS = ToolRegistry(boundary="administrator")
