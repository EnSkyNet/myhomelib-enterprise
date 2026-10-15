#!/usr/bin/env python3
"""Linux fresh-JVM A/B probe: wait4 peak RSS and Java thread allocations.
Compile ContentExtractionMemoryProbe first. Classpath files include its classes and
all runtime dependencies. Baseline uses the same probe and older production classes.
SHA-256 covers extracted text, chapter metadata and anchors; no book text is saved.
"""
import argparse
import csv
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--java', required=True, type=Path)
    p.add_argument('--classpath-file', required=True, type=Path)
    p.add_argument('--baseline-classpath-file', type=Path)
    p.add_argument('--output', required=True, type=Path)
    p.add_argument('--runs', type=int, default=3)
    p.add_argument('--heap', default='512m')
    p.add_argument('sources', nargs='+', type=Path)
    a = p.parse_args()
    if a.runs < 1: p.error('--runs must be positive')
    variants = []
    if a.baseline_classpath_file: variants.append(('before', a.baseline_classpath_file.read_text().strip()))
    variants.append(('after', a.classpath_file.read_text().strip()))
    expected = {}
    fields = ['variant', 'run', 'source', 'source_bytes', 'text_chars', 'chapters', 'anchors',
              'allocated_bytes', 'extraction_us', 'peak_rss_kib', 'sha256']
    a.output.parent.mkdir(parents=True, exist_ok=True)
    with a.output.open('w', newline='') as csv_out:
        writer = csv.DictWriter(csv_out, fieldnames=fields); writer.writeheader()
        for run in range(1, a.runs + 1):
            for source in a.sources:
                for label, cp in variants:
                    command = [str(a.java), '-Xms32m', '-Xmx' + a.heap, '-XX:+UseG1GC', '-Dfile.encoding=UTF-8',
                               '-cp', cp, 'com.myhomelibcorp.benchmark.content.ContentExtractionMemoryProbe', str(source.resolve())]
                    with tempfile.TemporaryFile(mode='w+') as stdout, tempfile.TemporaryFile(mode='w+') as stderr:
                        process = subprocess.Popen(command, stdout=stdout, stderr=stderr)
                        _, status, usage = os.wait4(process.pid, 0)
                        process.returncode = os.waitstatus_to_exitcode(status)
                        stdout.seek(0); stderr.seek(0)
                        output, errors = stdout.read(), stderr.read()
                    if process.returncode: raise RuntimeError(f'{label} {source.name}: exit {process.returncode}\n{errors}\n{output}')
                    data = json.loads(next(line for line in output.splitlines() if line.startswith('{"text_chars"')))
                    previous = expected.setdefault(str(source.resolve()), data['sha256'])
                    if data['sha256'] != previous: raise RuntimeError(f'Content/offset mismatch: {source.name}')
                    writer.writerow(dict(variant=label, run=run, source=source.name, source_bytes=source.stat().st_size,
                                         peak_rss_kib=usage.ru_maxrss, **data)); csv_out.flush()
                    print(f'{label} run={run} {source.name}: RSS={usage.ru_maxrss} KiB allocated={data["allocated_bytes"]}', flush=True)


if __name__ == '__main__': main()
