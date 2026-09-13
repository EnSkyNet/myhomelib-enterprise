#!/usr/bin/env python3
"""Fail closed for final Spring beans that require class-based proxying."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
FINAL_CLASS = re.compile(r"\b(?:public\s+)?final\s+class\s+(\w+)")
AOP_MARKERS = ("@Transactional", "@Async", "@Cacheable", "@CacheEvict", "@CachePut", "@Retryable")


def main() -> int:
    violations=[]
    inspected=0
    for path in sorted(ROOT.glob('*/src/main/java/**/*.java')):
        source=path.read_text(encoding='utf-8', errors='strict')
        match=FINAL_CLASS.search(source)
        if not match:
            continue
        inspected += 1
        reasons=[]
        # Spring Boot defaults to class-based AOP proxies. @Repository beans may be
        # proxied by persistence-exception translation even without @Transactional.
        if '@Repository' in source:
            reasons.append('@Repository exception-translation proxy')
        for marker in AOP_MARKERS:
            if marker in source:
                reasons.append(marker)
        if reasons:
            violations.append(f"{path.relative_to(ROOT)}: final {match.group(1)} is not proxyable ({', '.join(reasons)})")
    if violations:
        print('Spring proxyability check: FAIL')
        for v in violations:
            print(' - '+v)
        return 1
    print(f'Spring proxyability check: PASS ({inspected} final production classes inspected)')
    return 0

if __name__ == '__main__':
    sys.exit(main())
