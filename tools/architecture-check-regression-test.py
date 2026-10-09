#!/usr/bin/env python3
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
result = subprocess.run(
    [sys.executable, str(ROOT / "tools" / "architecture-check.py")],
    cwd=ROOT,
    text=True,
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    check=False,
)
output = result.stdout
marker = "Stage 6 remote UPSERT preservation guard missing"
if marker in output:
    print(output)
    raise SystemExit("FAIL: architecture checker still looks for the Stage-6 UPSERT contract in a stale location")
if "UI debt ratchet:" not in output or "non-value domain-model users" not in output:
    print(output)
    raise SystemExit("FAIL: expected independent UI debt ratchet was not reported")
print("PASS: Stage-6 UPSERT contract is resolved from the canonical SQL owner; independent UI debt remains visible")
