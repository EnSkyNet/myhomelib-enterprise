#!/usr/bin/env python3
"""Offline regression for safe connected-acceptance artifact ingestion."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


mod = load("gh_ingest", HERE / "github-acceptance-artifact-ingest.py")
fixture = load("gh_ingest_fixture", HERE / "v71-final-external-acceptance-check-test.py")


def make_blob(*, tamper_msi: bool = False, traversal: bool = False) -> bytes:
    payload = fixture.github_payload()
    prefix = "target/github-connected-acceptance/"
    files = {
        prefix + "github-connected-acceptance.json": json.dumps(payload).encode(),
        prefix + "github-connected-acceptance.md": b"# GitHub connected acceptance\n\nOverall: **PASS**\n",
        prefix + "acceptance-harness.sha256": fixture.HARNESS_MANIFEST,
        prefix + "candidate-windows/MyHomeLib-7.1.0.msi": b"candidate-msi" if not tamper_msi else b"tampered-msi",
        prefix + "candidate-windows/MyHomeLib-7.1.0.exe": b"candidate-exe",
        prefix + "candidate-windows/myhomelib-7.1.0-windows-amd64.zip": b"candidate-portable",
    }
    manifest = (
        f"{fixture.MSI_SHA}  MyHomeLib-7.1.0.msi\n"
        f"{fixture.EXE_SHA}  MyHomeLib-7.1.0.exe\n"
        f"{fixture.PORTABLE_SHA}  myhomelib-7.1.0-windows-amd64.zip\n"
    ).encode()
    files[prefix + "candidate-windows/candidate-windows.sha256"] = manifest
    if traversal:
        files["../escape.txt"] = b"bad"
    out = Path(tempfile.mkstemp(suffix=".zip")[1])
    try:
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
            for name, data in files.items():
                zf.writestr(name, data)
        return out.read_bytes()
    finally:
        out.unlink(missing_ok=True)


def expect_fail(fn, text: str) -> None:
    try:
        fn()
    except mod.IngestError as exc:
        assert text.lower() in str(exc).lower(), (text, str(exc))
    else:
        raise AssertionError(f"expected failure containing {text!r}")


def main() -> int:
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        blob = make_blob()
        out = root / "staged"
        record = mod.validate_and_stage(blob, out)
        assert record["overall"] == "PASS"
        assert record["remoteDigestVerified"] is False
        assert (out / "candidate-windows/MyHomeLib-7.1.0.exe").read_bytes() == b"candidate-exe"
        assert (out / "acceptance-harness.sha256").read_bytes() == fixture.HARNESS_MANIFEST
        staged = json.loads((out / "github-connected-acceptance-ingest.json").read_text())
        assert staged["windowsExeSha256"] == fixture.EXE_SHA
        assert staged["acceptanceHarnessManifestSha256"] == fixture.HARNESS_SHA

        remote = {
            "acceptanceRunId": 456,
            "acceptanceRunUrl": "https://github.com/owner/repo/actions/runs/456",
            "candidateSha": fixture.CANDIDATE_SHA,
            "repository": "owner/repo",
            "artifactId": 999,
            "artifactName": "github-connected-acceptance-456-1",
            "declaredDigest": "sha256:" + hashlib.sha256(blob).hexdigest(),
            "artifactZipSha256": hashlib.sha256(blob).hexdigest(),
        }
        record = mod.validate_and_stage(blob, root / "remote", remote=remote)
        assert record["remoteDigestVerified"] is True
        assert record["acceptanceRunId"] == 456

        expect_fail(lambda: mod.validate_and_stage(make_blob(tamper_msi=True), root / "bad"), "SHA-256 mismatch")
        expect_fail(lambda: mod.validate_and_stage(make_blob(traversal=True), root / "bad2"), "parent traversal")
        bad_remote = dict(remote, declaredDigest="sha256:" + "0" * 64)
        expect_fail(lambda: mod.validate_and_stage(blob, root / "bad3", remote=bad_remote), "declared digest")

    print("GitHub acceptance artifact ingest regression tests: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
