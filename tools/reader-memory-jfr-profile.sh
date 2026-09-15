#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-$ROOT/target/reader-memory-profile}"
mkdir -p "$OUT_DIR"

export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"

for mb in 20 50 100; do
  report="$OUT_DIR/reader-${mb}mb.json"
  jfr_file="$OUT_DIR/reader-${mb}mb.jfr"
  rm -f "$report" "$jfr_file" "$OUT_DIR/reader-${mb}mb-jfr-summary.txt"
  "$ROOT/tools/invoke-maven.sh" -B -ntp -pl myhomelib-benchmark -am \
    -Dtest=PerformanceBaselineTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dmhl.performance=true \
    -Dmhl.performance.sizes=1000 \
    -Dmhl.performance.readerMb="$mb" \
    -Dmhl.performance.report="$report" \
    "-Dmhl.test.jvmArgs=-XX:StartFlightRecording=filename=$jfr_file,settings=profile,dumponexit=true" \
    test
  [[ -s "$report" ]] || { echo "Missing memory report: $report" >&2; exit 2; }
  [[ -s "$jfr_file" ]] || { echo "Missing JFR recording: $jfr_file" >&2; exit 3; }
  if command -v jfr >/dev/null 2>&1; then
    jfr summary "$jfr_file" > "$OUT_DIR/reader-${mb}mb-jfr-summary.txt"
  fi
done

python3 - "$OUT_DIR" <<'PY_SUMMARY'
import json, pathlib, sys
out = pathlib.Path(sys.argv[1])
rows=[]
for mb in (20,50,100):
    p=out/f"reader-{mb}mb.json"
    data=json.loads(p.read_text(encoding='utf-8'))
    r=data['reader']
    rows.append((mb,r['fb2ParseMs'],r['epubParseMs'],r['peakHeapDeltaBytes'],r['openClose20RetainedBytes'],r['gcCollectionsDelta']))
lines=[
    '# Reader memory/JFR profile', '',
    '| Fixture | FB2 parse, ms | EPUB parse, ms | Peak heap delta | Retained after 20 open/close | GC collections |',
    '|---:|---:|---:|---:|---:|---:|'
]
for mb,fb2,epub,peak,retained,gc in rows:
    lines.append(f"| {mb} MB | {fb2:.1f} | {epub:.1f} | {peak/1024/1024:.1f} MB | {retained/1024/1024:.1f} MB | {gc} |")
lines += ['', 'Guardrails: parse < 15 s; peak heap delta < 768 MB; retained after 20 open/close < 256 MB.']
(out/'SUMMARY.md').write_text('\n'.join(lines)+'\n', encoding='utf-8')
print('\n'.join(lines))
PY_SUMMARY
