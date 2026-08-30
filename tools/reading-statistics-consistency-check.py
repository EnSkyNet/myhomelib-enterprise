#!/usr/bin/env python3
from pathlib import Path
import sys
ROOT=Path(__file__).resolve().parents[1]
errors=[]
def need(c,m):
    if not c: errors.append(m)
def t(p): return (ROOT/p).read_text(encoding='utf-8')

m=t('myhomelib-infrastructure/src/main/resources/db/migration/V41__reading_statistics_singleton.sql')
p=t('myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/statistics/ReadingStatisticsPort.java')
r=t('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteReadingStatisticsRepository.java')
s=t('myhomelib-application/src/main/java/com/myhomelibcorp/application/service/ReadingSessionService.java')
u=t('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java')
b=t('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/backup/VersionedUserDataTransferAdapter.java')

need('CREATE UNIQUE INDEX IF NOT EXISTS ux_reading_stats_book_id' in m, 'V41 UNIQUE(book_id) missing')
need('void recordSession(ReadingSessionRecord session)' in p, 'reading statistics port must expose atomic recordSession')
need('save(' not in p and 'updateProgress(' not in p, 'dead read-modify-write statistics API reintroduced')
need('ON CONFLICT(book_id) DO UPDATE SET' in r, 'repository atomic UPSERT missing')
need('total_reading_seconds=MAX(0,reading_stats.total_reading_seconds) + excluded.total_reading_seconds' in r, 'session duration must accumulate atomically')
need('reading_sessions=MAX(0,reading_stats.reading_sessions) + 1' in r, 'session count must increment atomically')
need('statisticsPort.recordSession' in s and 'activeSessions.remove' in s, 'Reader session finish must persist atomically exactly after active session removal')
need('readingSessionService.start' in u and 'finishReadingSession()' in u, 'Reader session start/finish runtime wiring missing')
need('clearedReadingStats' not in b and 'ON CONFLICT(book_id) DO UPDATE SET' in b, 'portable restore must use idempotent reading-stats UPSERT')

if errors:
    print('READING STATISTICS CONSISTENCY CHECK: FAILED')
    for e in errors: print(' -',e)
    sys.exit(1)
print('READING STATISTICS CONSISTENCY CHECK: PASS')
print(' - V41 singleton/dedup contract: PASS')
print(' - atomic recordSession UPSERT; no read-modify-write API: PASS')
print(' - portable restore true UPSERT: PASS')
print(' - Reader session start/finish runtime wiring: PASS')
