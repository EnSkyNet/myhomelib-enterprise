#!/usr/bin/env python3
"""Regression: build-check child Python gates must not mutate a clean source tree."""
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from types import SimpleNamespace

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "tools/build-check-v7.py"

spec = importlib.util.spec_from_file_location("mhl_build_check_v7", TARGET)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

calls: list[dict] = []

def fake_run(*args, **kwargs):
    calls.append(kwargs)
    return SimpleNamespace(returncode=0, stdout="")

module.subprocess.run = fake_run
for fn in (
    module.check_ui_action_coverage,
    module.check_ui_action_behavior,
    module.check_native_file_chooser_boundary,
    module.check_user_function_documentation,
):
    fn()

assert calls, "expected child Python gate invocations"
for kwargs in calls:
    env = kwargs.get("env")
    assert env is not None and env.get("PYTHONDONTWRITEBYTECODE") == "1", \
        "build-check child Python gates must set PYTHONDONTWRITEBYTECODE=1"

print("PASS: build-check child Python gates are configured as non-mutating")
