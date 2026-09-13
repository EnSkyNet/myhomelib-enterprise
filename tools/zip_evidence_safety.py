#!/usr/bin/env python3
"""Shared ZIP safety guards for external-acceptance evidence.

The acceptance flow handles archives obtained from CI and Windows hosts.  This
module centralizes the fail-closed metadata checks so every validator applies
the same limits before reading or extracting members.
"""
from __future__ import annotations

import re
import stat
import zipfile
from pathlib import PurePosixPath
from typing import Iterable, Type

MAX_EVIDENCE_FILES = 4096
MAX_EVIDENCE_MEMBER_BYTES = 512 * 1024 * 1024
MAX_EVIDENCE_TOTAL_BYTES = 2 * 1024 * 1024 * 1024
_DRIVE_RE = re.compile(r"^[A-Za-z]:$")


def _fail(error_type: Type[Exception], message: str):
    raise error_type(message)


def normalized_member_name(info: zipfile.ZipInfo, *, label: str, error_type: Type[Exception]) -> str:
    raw = info.filename.replace("\\", "/")
    path = PurePosixPath(raw)
    if not raw or raw.startswith("/") or raw.startswith("//") or path.is_absolute():
        _fail(error_type, f"{label}: unsafe absolute ZIP member: {raw!r}")
    if path.parts and _DRIVE_RE.fullmatch(path.parts[0]):
        _fail(error_type, f"{label}: unsafe drive-qualified ZIP member: {raw!r}")
    if ".." in path.parts:
        _fail(error_type, f"{label}: unsafe parent traversal ZIP member: {raw!r}")
    if "\x00" in raw:
        _fail(error_type, f"{label}: NUL byte in ZIP member name")
    if info.flag_bits & 0x1:
        _fail(error_type, f"{label}: encrypted ZIP member is not allowed: {raw!r}")

    unix_type = (info.external_attr >> 16) & 0o170000
    if unix_type and unix_type not in {stat.S_IFREG, stat.S_IFDIR}:
        _fail(error_type, f"{label}: non-regular ZIP member is not allowed: {raw!r}")
    return str(path)


def validate_infos(
    infos: Iterable[zipfile.ZipInfo],
    *,
    label: str,
    error_type: Type[Exception] = ValueError,
    max_files: int = MAX_EVIDENCE_FILES,
    max_member_bytes: int = MAX_EVIDENCE_MEMBER_BYTES,
    max_total_bytes: int = MAX_EVIDENCE_TOTAL_BYTES,
) -> dict[str, zipfile.ZipInfo]:
    files = [info for info in infos if not info.is_dir()]
    if not (1 <= len(files) <= max_files):
        _fail(error_type, f"{label}: unexpected file count: {len(files)}")

    result: dict[str, zipfile.ZipInfo] = {}
    total = 0
    for info in files:
        name = normalized_member_name(info, label=label, error_type=error_type)
        if name in result:
            _fail(error_type, f"{label}: duplicate normalized ZIP member: {name}")
        if info.file_size < 0 or info.file_size > max_member_bytes:
            _fail(error_type, f"{label}: ZIP member exceeds safety limit: {name}")
        total += info.file_size
        if total > max_total_bytes:
            _fail(error_type, f"{label}: uncompressed size exceeds safety limit")
        result[name] = info
    return result


def inspect_open_zip(
    zf: zipfile.ZipFile,
    *,
    label: str,
    error_type: Type[Exception] = ValueError,
    max_files: int = MAX_EVIDENCE_FILES,
    max_member_bytes: int = MAX_EVIDENCE_MEMBER_BYTES,
    max_total_bytes: int = MAX_EVIDENCE_TOTAL_BYTES,
) -> dict[str, zipfile.ZipInfo]:
    return validate_infos(
        zf.infolist(),
        label=label,
        error_type=error_type,
        max_files=max_files,
        max_member_bytes=max_member_bytes,
        max_total_bytes=max_total_bytes,
    )
