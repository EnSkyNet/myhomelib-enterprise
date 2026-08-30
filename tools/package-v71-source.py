#!/usr/bin/env python3
"""Build and verify the MyHomeLib Enterprise v7.1 source release artifacts.

This is intentionally an offline source-packaging gate. It never claims Maven/GitHub
success; those remain separate connected-machine acceptance requirements.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BASELINE = ROOT.parent / "baseline"
EXCLUDED_DIRS = {".git", "target", "dist", ".idea", ".gradle", "__pycache__", ".pytest_cache"}
EXCLUDED_FILES = {".DS_Store", "Thumbs.db"}


def is_excluded(rel: Path) -> bool:
    return any(part in EXCLUDED_DIRS for part in rel.parts) or rel.name in EXCLUDED_FILES


def copy_clean(src: Path, dst: Path) -> None:
    if dst.exists():
        shutil.rmtree(dst)
    dst.mkdir(parents=True)
    for root, dirs, files in os.walk(src):
        root_path = Path(root)
        rel_root = root_path.relative_to(src)
        dirs[:] = [d for d in dirs if not is_excluded(rel_root / d)]
        for name in files:
            rel = rel_root / name
            if is_excluded(rel):
                continue
            source = src / rel
            target = dst / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            if source.is_symlink():
                target.symlink_to(os.readlink(source))
            else:
                shutil.copy2(source, target)


def run(cmd: list[str], cwd: Path, *, capture: bool = False, accepted: set[int] | None = None) -> subprocess.CompletedProcess[str]:
    accepted = accepted or {0}
    print("[release]", " ".join(cmd), flush=True)
    cp = subprocess.run(cmd, cwd=cwd, text=True, stdout=subprocess.PIPE if capture else None,
                        stderr=subprocess.STDOUT if capture else None)
    if cp.returncode not in accepted:
        if capture and cp.stdout:
            print(cp.stdout, file=sys.stderr)
        raise RuntimeError(f"command failed ({cp.returncode}): {' '.join(cmd)}")
    return cp


def offline_checks(tree: Path) -> None:
    run([sys.executable, "tools/build-check-v7.py"], tree)
    run([sys.executable, "tools/static_release_check.py"], tree)
    run([sys.executable, "tools/architecture-check.py"], tree)
    run([sys.executable, "tools/catalog-lifecycle-regression-check.py"], tree)
    run([sys.executable, "tools/v71-standalone-java-smoke.py"], tree)
    run([sys.executable, "tools/stage8-9-filter-table-check.py"], tree)
    run([sys.executable, "tools/stage24-performance-check.py"], tree)
    run([sys.executable, "tools/stage25c-search-sync-refactor-check.py"], tree)

    yaml_code = r'''
from pathlib import Path
try:
    import yaml
except Exception as exc:
    raise SystemExit(f"PyYAML unavailable for workflow syntax check: {exc}")
for p in sorted(Path('.github/workflows').glob('*.y*ml')):
    yaml.safe_load(p.read_text(encoding='utf-8'))
    print('YAML OK', p)
'''
    run([sys.executable, "-c", yaml_code], tree)

    shell_files = sorted(p for p in tree.glob("*.sh") if p.is_file())
    for path in shell_files:
        run(["bash", "-n", str(path.relative_to(tree))], tree)


def create_zip(source: Path, archive: Path, top_dir: str) -> None:
    if archive.exists():
        archive.unlink()
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for path in sorted(source.rglob("*")):
            if path.is_dir():
                continue
            rel = path.relative_to(source)
            arc = Path(top_dir) / rel
            if path.is_symlink():
                info = zipfile.ZipInfo(str(arc).replace(os.sep, "/"))
                info.create_system = 3
                info.external_attr = (stat.S_IFLNK | 0o777) << 16
                zf.writestr(info, os.readlink(path))
                continue
            info = zipfile.ZipInfo.from_file(path, str(arc).replace(os.sep, "/"))
            # Preserve executable bits (notably mvnw/build scripts).
            mode = path.stat().st_mode
            info.external_attr = (mode & 0xFFFF) << 16
            with path.open("rb") as fh:
                zf.writestr(info, fh.read(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def create_patch(baseline: Path, current_clean: Path, patch: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="mhl-v71-patch-") as td:
        repo = Path(td) / "repo"
        copy_clean(baseline, repo)
        run(["git", "init", "-q"], repo)
        run(["git", "config", "user.email", "release-check@localhost"], repo)
        run(["git", "config", "user.name", "MyHomeLib Release Check"], repo)
        run(["git", "add", "-A"], repo)
        run(["git", "commit", "-qm", "MyHomeLib Enterprise v7 baseline"], repo)
        # rsync --delete gives Git a precise view of additions, edits and deletions while preserving .git.
        run(["rsync", "-a", "--delete", "--exclude=.git/", f"{current_clean}/", f"{repo}/"], repo)
        # Stage the complete resulting tree so the patch includes additions/deletions as well as edits.
        # Plain `git diff HEAD` omits untracked v7.1 files and can silently produce an incomplete upgrade patch.
        run(["git", "add", "-A"], repo)
        cp = run(["git", "diff", "--binary", "--cached", "HEAD", "--", "."], repo, capture=True, accepted={0})
        patch.write_text(cp.stdout or "", encoding="utf-8")
        if patch.stat().st_size == 0:
            raise RuntimeError("generated v7 -> v7.1 patch is empty")


def _tree_manifest(root: Path) -> dict[str, tuple[str, str, int]]:
    manifest: dict[str, tuple[str, str, int]] = {}
    for path in sorted(root.rglob("*")):
        if path.is_dir():
            continue
        rel = str(path.relative_to(root)).replace(os.sep, "/")
        if any(part in EXCLUDED_DIRS for part in Path(rel).parts):
            continue
        if path.is_symlink():
            manifest[rel] = ("L", os.readlink(path), 0o111)
        else:
            manifest[rel] = ("F", sha256(path), path.stat().st_mode & 0o111)
    return manifest


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _zip_manifest(archive: Path, top_dir: str) -> dict[str, tuple[str, str, int]]:
    manifest: dict[str, tuple[str, str, int]] = {}
    prefix = top_dir.rstrip("/") + "/"
    with zipfile.ZipFile(archive, "r") as zf:
        for info in zf.infolist():
            if info.is_dir():
                continue
            name = info.filename
            if not name.startswith(prefix):
                raise RuntimeError(f"unexpected ZIP member outside {top_dir}: {name}")
            rel = name[len(prefix):]
            if not rel:
                continue
            mode = (info.external_attr >> 16) & 0xFFFF
            data = zf.read(info)
            if stat.S_ISLNK(mode):
                manifest[rel] = ("L", data.decode("utf-8"), mode & 0o111)
            else:
                manifest[rel] = ("F", _sha256_bytes(data), mode & 0o111)
    return manifest


def verify_patch_matches_archive(baseline: Path, patch: Path, archive: Path, top_dir: str) -> None:
    """Prove that applying the upgrade patch to v7 yields the same release tree as the source ZIP."""
    with tempfile.TemporaryDirectory(prefix="mhl-v71-patch-verify-") as td:
        td_path = Path(td)
        patched = td_path / "patched"
        copy_clean(baseline, patched)
        run(["git", "apply", "--check", "--whitespace=nowarn", str(patch)], patched)
        run(["git", "apply", "--binary", "--whitespace=nowarn", str(patch)], patched)
        patched_manifest = _tree_manifest(patched)
        # Read content and executable bits directly from ZipInfo. Python extractall() does not
        # reliably restore Unix mode bits and would create false mismatches for mvnw/scripts.
        zip_manifest = _zip_manifest(archive, top_dir)
        if patched_manifest != zip_manifest:
            missing = sorted(set(zip_manifest) - set(patched_manifest))
            extra = sorted(set(patched_manifest) - set(zip_manifest))
            different = sorted(k for k in set(patched_manifest) & set(zip_manifest)
                               if patched_manifest[k] != zip_manifest[k])
            raise RuntimeError(
                "patch/ZIP release trees differ: "
                f"missing={missing[:10]} extra={extra[:10]} different={different[:10]}"
            )
        print(f"[release] PATCH <-> ZIP equivalence PASS ({len(zip_manifest)} files)", flush=True)


def verify_archive(archive: Path, top_dir: str) -> None:
    with tempfile.TemporaryDirectory(prefix="mhl-v71-extracted-") as td:
        td_path = Path(td)
        with zipfile.ZipFile(archive, "r") as zf:
            bad = [n for n in zf.namelist() if Path(n).is_absolute() or ".." in Path(n).parts]
            if bad:
                raise RuntimeError(f"unsafe ZIP members: {bad[:5]}")
            zf.extractall(td_path)
        root = td_path / top_dir
        if not root.is_dir():
            raise RuntimeError(f"ZIP top-level directory missing: {top_dir}")
        for forbidden in EXCLUDED_DIRS:
            found = list(root.rglob(forbidden))
            if found:
                raise RuntimeError(f"forbidden generated directory in ZIP: {found[0].relative_to(root)}")
        offline_checks(root)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    ap.add_argument("--out-dir", type=Path, default=ROOT.parent / "release-v7.1")
    args = ap.parse_args()
    baseline = args.baseline.resolve()
    out = args.out_dir.resolve()
    if not baseline.is_dir():
        raise SystemExit(f"baseline not found: {baseline}")
    out.mkdir(parents=True, exist_ok=True)

    top = "myhomelib-enterprise-v7.1"
    archive = out / "myhomelib-enterprise-v7.1.zip"
    patch = out / "myhomelib-v7-to-v7.1.patch"
    checksum = out / "myhomelib-enterprise-v7.1.zip.sha256"
    report = out / "RELEASE-ARTIFACT-VALIDATION-v7.1.txt"

    # Verify the source tree before copying; this prevents packaging a known-bad workspace.
    offline_checks(ROOT)

    with tempfile.TemporaryDirectory(prefix="mhl-v71-clean-") as td:
        clean = Path(td) / top
        copy_clean(ROOT, clean)
        offline_checks(clean)
        create_patch(baseline, clean, patch)
        create_zip(clean, archive, top)

    verify_archive(archive, top)
    verify_patch_matches_archive(baseline, patch, archive, top)
    digest = sha256(archive)
    checksum.write_text(f"{digest}  {archive.name}\n", encoding="ascii")
    report.write_text(
        "MyHomeLib Enterprise v7.1 source artifact validation\n"
        "==================================================\n"
        "Status: OFFLINE SOURCE ARTIFACT CHECKS PASS\n"
        f"ZIP: {archive.name}\n"
        f"SHA-256: {digest}\n"
        "Verification: source tree PASS; clean copied tree PASS; freshly extracted ZIP PASS; patch↔ZIP equivalence PASS.\n"
        "Included gates: migration immutability/upgrade, metadata migrations, XML/FXML, source invariants,\n"
        "static release checks, architecture/lifecycle regression, standalone JDK v7.1 runtime smoke,\n"
        "Stage 8+9, Stage 24 contract, Stage 25C, workflow YAML and root shell syntax.\n"
        "NOT VERIFIED: ./mvnw clean verify -Pproduction; real GitHub Actions Ubuntu/Windows/macOS;\n"
        "JavaFX/jpackage runtime smoke; connected JVM/Lucene before/after benchmark.\n",
        encoding="utf-8",
    )

    print("[release] SOURCE ARTIFACT CHECKS PASS")
    for path in (archive, patch, checksum, report):
        print(f"[release] {path} ({path.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
