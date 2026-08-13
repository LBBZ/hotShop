from __future__ import annotations

import os
from types import ModuleType

import pytest

EXPECTED_PROJECT_PLUGINS = {"pytest_asyncio.plugin", "pytest_cov.plugin"}


def test_pytest_third_party_plugins_are_explicit(pytestconfig: pytest.Config) -> None:
    """Fail if pytest starts loading a project plugin implicitly."""
    assert os.environ.get("PYTEST_DISABLE_PLUGIN_AUTOLOAD") == "1"

    loaded_modules = {
        plugin.__name__
        for _, plugin in pytestconfig.pluginmanager.list_name_plugin()
        if isinstance(plugin, ModuleType)
    }
    project_plugins = {
        name for name in loaded_modules if not name.startswith("_pytest.") and name != "conftest"
    }

    assert "pytest_asyncio.plugin" in project_plugins
    assert project_plugins <= EXPECTED_PROJECT_PLUGINS
    assert not any("langsmith" in name.casefold() for name in loaded_modules)
