#!/usr/bin/env python3
"""Regression tests for shared external-evidence ZIP metadata guards."""
from __future__ import annotations

import importlib.util
import stat
from pathlib import Path
from zipfile import ZipInfo

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("zip_evidence_safety", HERE / "zip_evidence_safety.py")
assert SPEC and SPEC.loader
mod = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mod)


def info(name: str, size: int = 1) -> ZipInfo:
    value = ZipInfo(name)
    value.file_size = size
    return value


def expect_fail(label: str, fn) -> None:
    try:
        fn()
    except ValueError:
        return
    raise AssertionError(f"ZIP safety negative regression did not fail: {label}")


def main() -> int:
    result = mod.validate_infos([info("a.json", 10), info("dir/b.png", 20)], label="fixture")
    assert set(result) == {"a.json", "dir/b.png"}

    expect_fail("parent traversal", lambda: mod.validate_infos([info("../secret")], label="fixture"))
    expect_fail("absolute", lambda: mod.validate_infos([info("/secret")], label="fixture"))
    expect_fail("drive path", lambda: mod.validate_infos([info("C:/secret")], label="fixture"))
    expect_fail(
        "duplicate normalized member",
        lambda: mod.validate_infos([info("dir\\a"), info("dir/a")], label="fixture"),
    )

    link = info("link")
    link.create_system = 3
    link.external_attr = (stat.S_IFLNK | 0o777) << 16
    expect_fail("symlink", lambda: mod.validate_infos([link], label="fixture"))

    encrypted = info("encrypted")
    encrypted.flag_bits |= 0x1
    expect_fail("encrypted", lambda: mod.validate_infos([encrypted], label="fixture"))
    expect_fail(
        "member bound",
        lambda: mod.validate_infos([info("large", 11)], label="fixture", max_member_bytes=10),
    )
    expect_fail(
        "total bound",
        lambda: mod.validate_infos(
            [info("a", 6), info("b", 6)], label="fixture", max_member_bytes=10, max_total_bytes=10
        ),
    )
    expect_fail(
        "file-count bound",
        lambda: mod.validate_infos([info("a"), info("b")], label="fixture", max_files=1),
    )

    print("External evidence ZIP safety regression tests: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
