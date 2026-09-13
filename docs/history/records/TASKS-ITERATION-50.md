# Iteration 50 Tasks — MHL-303 + MHL-307

| Task | Result | Status |
|---|---|---|
| Persistent background indexing priority queue | HIGH/NORMAL/LOW queue; worker execution off caller/UI thread | DONE |
| Resume after restart | Atomic per-collection checkpoint + startup restore | DONE |
| Pause/resume/cancel | Non-blocking controls with race-safe cancellation | DONE |
| Resource profiles | Eco/Balanced/Fast CPU worker limits + I/O rates | DONE |
| Pause on battery | Cached async system power detection | DONE |
| Settings persistence/UI | Profile + pause-on-battery persisted and editable | DONE |
| I/O throttling | Rate-limited input stream with cancellation slices | DONE |
| Shutdown durability | Drain async checkpoint executor before final snapshot | DONE |
| Acceptance | Static 11/11; full reactor 13/13; 864 tests 0/0 | DONE |
