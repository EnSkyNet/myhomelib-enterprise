#!/usr/bin/env python3
"""Interactive/checklist evidence recorder for post-R5 device/provider/browser/corpus gates."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

SHA40 = re.compile(r"^[0-9a-f]{40}$")

SCENARIOS = {
    "kobo": ("physical-kobo", "kobo-acceptance", [
        ("KOB-01", "First sync completes on a physical Kobo device."),
        ("KOB-02", "Synced library/book appears on Kobo."),
        ("KOB-03", "Book opens and can be read on Kobo."),
        ("KOB-04", "Reading progress syncs Kobo → MyHomeLib."),
        ("KOB-05", "Reading progress syncs MyHomeLib → Kobo."),
        ("KOB-06", "Metadata and cover are correct on Kobo."),
        ("KOB-07", "Reconnect after disconnect succeeds."),
        ("KOB-08", "Credential revoke/rotation invalidates old access and new credential works."),
        ("KOB-09", "Repeat sync after reconnect/revoke completes without stale state."),
    ]),
    "readwise": ("real-readwise", "readwise-acceptance", [
        ("RW-01", "Authentication succeeds with a real Readwise account/token."),
        ("RW-02", "Create export succeeds and remote records are correct."),
        ("RW-03", "Update/dedup rerun avoids duplicate unchanged records."),
        ("RW-04", "Rate-limit response is handled without corrupting local/remote state."),
        ("RW-05", "Timeout/network partial failure is surfaced and bounded."),
        ("RW-06", "Idempotent retry completes correctly."),
        ("RW-07", "Restart/retry creates no duplicate remote records."),
    ]),
    "pwa": ("pwa-real-browser", "pwa-acceptance", [
        ("PWA-01", "PWA installs in a supported unrestricted browser."),
        ("PWA-02", "App shell opens offline."),
        ("PWA-03", "Explicitly saved book opens offline."),
        ("PWA-04", "Browser restart while offline preserves supported offline behavior."),
        ("PWA-05", "Reconnect replays pending progress correctly."),
        ("PWA-06", "Auth/token revocation and reconnect do not silently keep a stale session."),
    ]),
    "comics": ("real-comic-corpus", "comic-corpus-acceptance", [
        ("COMIC-01", "Large real CBR opens and remains usable."),
        ("COMIC-02", "Large real CB7 opens and remains usable."),
        ("COMIC-03", "Large/nonstandard image dimensions render safely."),
        ("COMIC-04", "Exact duplicate detection works on the real corpus."),
        ("COMIC-05", "Corrupt entry handling continues safely with visible diagnostics."),
        ("COMIC-06", "Cancel stops active work cleanly."),
        ("COMIC-07", "Restart/reopen returns to a usable state."),
        ("COMIC-08", "Reader/details memory is released after close with no retained runaway growth."),
    ]),
}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("scenario", choices=sorted(SCENARIOS))
    p.add_argument("--candidate-sha", required=True)
    p.add_argument("--root", type=Path, default=Path("target/post-r5-live-evidence"))
    p.add_argument("--checklist-only", action="store_true")
    args = p.parse_args(argv or sys.argv[1:])

    candidate = args.candidate_sha.strip().lower()
    if SHA40.fullmatch(candidate) is None:
        raise SystemExit("--candidate-sha must be a full 40-character Git SHA")

    scenario, base_name, checks = SCENARIOS[args.scenario]
    out_dir = args.root / scenario
    evidence_dir = out_dir / "evidence"
    out_dir.mkdir(parents=True, exist_ok=True)
    evidence_dir.mkdir(parents=True, exist_ok=True)
    rows: list[dict] = []

    for cid, check in checks:
        if args.checklist_only:
            rows.append({"id": cid, "check": check, "outcome": "PENDING", "note": "", "evidence": "", "evidenceSha256": ""})
            continue
        print(f"\n{cid} {check}")
        while True:
            outcome = input(f"{cid} result [PASS/FAIL/BLOCKED]: ").strip().upper()
            if outcome in {"PASS", "FAIL", "BLOCKED"}:
                break
        note = input("Evidence note: ").strip()
        evidence_ref = ""
        evidence_sha256 = ""
        if outcome == "PASS":
            if not note:
                raise SystemExit(f"{cid}: PASS requires a non-empty evidence note")
            source = Path(input("Evidence file path: ").strip()).expanduser().resolve()
            if not source.is_file() or source.stat().st_size <= 0:
                raise SystemExit(f"{cid}: evidence file is missing/empty: {source}")
            dest = evidence_dir / f"{cid}-{source.name}"
            shutil.copy2(source, dest)
            evidence_ref = dest.relative_to(out_dir).as_posix()
            evidence_sha256 = sha256(dest)
        rows.append({
            "id": cid, "check": check, "outcome": outcome, "note": note,
            "evidence": evidence_ref, "evidenceSha256": evidence_sha256,
        })

    overall = "PENDING" if args.checklist_only else ("PASS" if all(r["outcome"] == "PASS" for r in rows) else "FAIL")
    stamp = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    payload = {
        "schemaVersion": 1,
        "scenario": scenario,
        "timestamp": stamp,
        "overall": overall,
        "candidateSha": candidate,
        "results": rows,
    }
    json_path = out_dir / f"{base_name}.json"
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    md = [f"# MyHomeLib — {scenario}", "", f"- Candidate SHA: `{candidate}`", f"- Overall: **{overall}**", "",
          "| ID | Result | Check | Evidence | Evidence SHA-256 | Note |", "|---|---|---|---|---|---|"]
    for row in rows:
        clean = lambda s: str(s).replace("|", "\\|").replace("\n", " ")
        md.append(f"| {row['id']} | {row['outcome']} | {clean(row['check'])} | {clean(row['evidence'])} | {clean(row['evidenceSha256'])} | {clean(row['note'])} |")
    md_path = out_dir / f"{base_name}.md"
    md_path.write_text("\n".join(md) + "\n", encoding="utf-8")
    print(f"Report: {json_path}")
    print(f"SHA-256: {sha256(json_path)}")
    print(f"Overall: {overall}")
    return 0 if args.checklist_only or overall == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
