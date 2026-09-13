#!/usr/bin/env python3
"""Static closure gate for Iteration 50 / MHL-303 + MHL-307."""
from pathlib import Path
import csv
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = []

def text(rel):
    return (ROOT / rel).read_text(encoding="utf-8")

def contains(rel, *needles):
    value = text(rel)
    return all(needle in value for needle in needles)

def check(label, ok):
    checks.append((label, bool(ok)))
    print(("PASS" if ok else "FAIL") + ": " + label)

queue = "myhomelib-application/src/main/java/com/myhomelibcorp/application/content/indexing/ContentIndexingQueueService.java"
check("background queue is priority-based and supports pause/resume/cancel",
      contains(queue, "PriorityBlockingQueue", "pause()", "resume()", "cancel(String taskId)", "task.priority().order()"))
check("queue work and durable checkpoints run on daemon executors",
      contains(queue, "mhl-content-index-worker", "mhl-content-index-coordinator", "mhl-content-index-checkpoint", "requestCheckpoint"))
check("restart checkpoint port persists unfinished tasks",
      contains("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/contentindexing/FileContentIndexQueueCheckpointAdapter.java",
               "task.count", "enqueuedAt", "paused", "AtomicFileSupport.moveReplacing"))
check("startup restores active collection indexing queue",
      contains("myhomelib-bootstrap/src/main/java/com/myhomelibcorp/startup/ContentIndexingStartupTask.java",
               "queueService.restore", "activeCollection().getId()") and
      contains("myhomelib-bootstrap/src/main/java/com/myhomelibcorp/startup/StartupOrchestrator.java",
               "ContentIndexingStartupTask", "contentIndexingStartupTask"))
check("file processor applies cancellation and I/O rate limiting",
      contains("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/contentindexing/FileContentIndexingTaskProcessor.java",
               "RateLimitedInputStream", "ioBytesPerSecond", "control.isCancelled", "LockSupport.parkNanos"))
check("power-state detection is cached and off caller/UI thread",
      contains("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/contentindexing/SystemPowerStateAdapter.java",
               "mhl-power-state-detector", "refreshRunning", "detector.execute"))
check("Eco/Balanced/Fast profiles define CPU, I/O and battery policy",
      contains("myhomelib-application/src/main/java/com/myhomelibcorp/application/content/indexing/IndexingResourceProfile.java",
               "ECO", "BALANCED", "FAST", "workerThreads", "ioBytesPerSecond", "defaultPauseOnBattery"))
check("indexing performance settings persist profile and battery preference",
      contains("myhomelib-application/src/main/java/com/myhomelibcorp/application/content/indexing/IndexingPerformanceSettingsService.java",
               "content.indexing.profile", "content.indexing.pauseOnBattery", "settings.put", "putBoolean"))
check("Settings UI exposes indexing profile and pause-on-battery",
      contains("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/ApplicationSettingsDialog.java",
               'tab("Індексування"', "IndexingSettingsEditor", "IndexingResourceProfile", "pauseOnBattery"))
check("queue contract tests cover priority/restart/cancel/battery limits",
      contains("myhomelib-application/src/test/java/com/myhomelibcorp/application/content/indexing/ContentIndexingQueueServiceTest.java",
               "priorityQueueRunsOffCallerThread", "checkpointRestoresUnfinishedTasksAfterRestart",
               "cancelSignalsActiveProcessor", "batteryPauseAndBalancedWorkerLimitAreEnforced"))

benchmark = ROOT / "docs/history/records/ITERATION-50-INDEXING-QUEUE-BENCHMARK.csv"
benchmark_ok = False
if benchmark.is_file():
    with benchmark.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    if rows:
        row = rows[-1]
        benchmark_ok = (row.get("profile") == "BALANCED"
                        and int(row.get("tasks", "0")) >= 300
                        and int(row.get("completed", "0")) == int(row.get("tasks", "-1"))
                        and int(row.get("max_active", "999")) <= int(row.get("worker_limit", "0"))
                        and int(row.get("p95_enqueue_us", "999999")) < 50_000)
check("Balanced benchmark completes >=300 tasks within worker bound and p95 enqueue <50ms", benchmark_ok)

if not all(ok for _, ok in checks):
    sys.exit(1)
print(f"Iteration 50 indexing queue check: PASS ({len(checks)}/{len(checks)})")
