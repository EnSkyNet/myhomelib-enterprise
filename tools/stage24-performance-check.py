#!/usr/bin/env python3
from __future__ import annotations
import json, sys
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
errors=[]
def need(cond,msg):
    if not cond: errors.append(msg)
def text(rel): return (ROOT/rel).read_text(encoding='utf-8')

baseline_path=ROOT/'docs/performance-baseline.json'
need(baseline_path.exists(),'docs/performance-baseline.json missing')
if baseline_path.exists():
    data=json.loads(baseline_path.read_text(encoding='utf-8'))
    sizes={int(r['books']) for r in data.get('results',[])}
    need({100_000,500_000,1_000_000}.issubset(sizes),'100k/500k/1M baseline profiles missing')
    need(data.get('guardrails',{}).get('pass') is True,'stored Stage24 guardrails are not PASS')
    for r in data.get('results',[]):
        if int(r['books']) not in {100_000,500_000,1_000_000}: continue
        need(r['queries']['catalog_first_page']['p95_ms'] < 1000, f"first-page regression in stored baseline {r['books']}")
        need(r['queries']['navigation_authors_A']['p95_ms'] < 3000, f"author facet regression in stored baseline {r['books']}")
        need(r['queries']['libid_lookup']['p95_ms'] < 100, f"LibID lookup regression in stored baseline {r['books']}")
        plan=' '.join(r.get('plans',{}).get('libid',[]))
        need('idx_books_lib_id' in plan, f"LibID plan missing index at {r['books']}")
        aplan=' '.join(r.get('plans',{}).get('author_initial',[]))
        need('idx_authors_navigation_initial' in aplan, f"author initial plan missing index at {r['books']}")

suite=text('myhomelib-benchmark/src/test/java/com/myhomelibcorp/benchmark/PerformanceBaselineTest.java')
for marker in ['EnabledIfSystemProperty','MemoryMXBean','GarbageCollectorMXBean','Fb2StreamingParser','EpubParser','ByteBuffersDirectory','mhl.performance.sizes','importProbeBooksPerSec','peakHeapDeltaBytes']:
    need(marker in suite,f'JVM performance suite missing {marker}')

parent=text('pom.xml')
need('<id>performance</id>' in parent and '<mhl.performance>true</mhl.performance>' in parent,'Maven performance profile missing')
benchpom=text('myhomelib-benchmark/pom.xml')
need('<artifactId>myhomelib-reader</artifactId>' in benchpom,'benchmark module must depend on reader for reader baselines')

workflow=text('.github/workflows/performance-baseline.yml')
for marker in ['workflow_dispatch','schedule:','-Pperformance','stage24-performance-baseline.py','performance-baseline-jvm.json']:
    need(marker in workflow,f'performance workflow missing {marker}')

runner=text('tools/stage24-performance-baseline.py')
for marker in ['100_000, 500_000, 1_000_000','EXPLAIN QUERY PLAN','import_probe','navigation_authors_A','catalog_filtered_page','resource.getrusage']:
    need(marker in runner,f'offline runner missing {marker}')
need((ROOT/'docs/PERFORMANCE_BASELINE.md').exists(),'performance baseline documentation missing')

for p in [ROOT/'pom.xml',ROOT/'myhomelib-benchmark/pom.xml']:
    try: ET.parse(p)
    except Exception as e: errors.append(f'XML invalid {p.name}: {e}')

if errors:
    print('STAGE 24 PERFORMANCE CHECK: FAILED')
    for e in errors: print(' -',e)
    sys.exit(1)
print('STAGE 24 PERFORMANCE CHECK: PASS')
print(' - stored 100k/500k/1M SQL baseline + query-plan indexes: PASS')
print(' - opt-in JVM heap/GC + Lucene + huge FB2/EPUB suite: PRESENT')
print(' - Maven performance profile + scheduled/manual CI workflow: PRESENT')
