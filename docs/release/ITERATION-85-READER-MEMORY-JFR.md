# Iteration 85 — Reader memory / JFR report

**Date:** 2026-09-14  
**Environment:** Linux / OpenJDK 21.0.11

The Reader benchmark generates real synthetic FB2 and EPUB fixtures at 20, 50 and 100 MB and records a Java Flight Recording for each run. Each size also performs 20 open/close cycles to observe retained heap.

| Fixture | FB2 parse, ms | EPUB parse, ms | Peak heap delta | Retained after 20 open/close | GC collections |
|---:|---:|---:|---:|---:|---:|
| 20 MB | 313.2 | 349.7 | 79.3 MB | 0.0 MB | 126 |
| 50 MB | 606.0 | 757.9 | 183.6 MB | 0.0 MB | 319 |
| 100 MB | 1107.0 | 1107.7 | 497.6 MB | 0.0 MB | 397 |

Configured guardrails:

- parse < 15 s;
- peak heap delta < 768 MB;
- retained after 20 open/close < 256 MB.

**Result: PASS at 20, 50 and 100 MB.**

The raw `.jfr` recordings are distributed separately from the source archive so the source package stays clean and reproducible.
