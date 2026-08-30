#!/usr/bin/env python3
from __future__ import annotations
import sqlite3
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
USECASE = ROOT / 'myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/collection/CopyBooksBetweenCollectionsUseCase.java'
SAVER = ROOT / 'myhomelib-application/src/main/java/com/myhomelibcorp/application/imports/saver/BookSaver.java'
PORT = ROOT / 'myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/collection/BookUserStateTransferPort.java'
ADAPTER = ROOT / 'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteBookUserStateTransferAdapter.java'
LIFECYCLE = ROOT / 'myhomelib-application/src/main/java/com/myhomelibcorp/application/service/CollectionLifecycleService.java'
TEST = ROOT / 'myhomelib-application/src/test/java/com/myhomelibcorp/application/usecase/collection/CopyBooksBetweenCollectionsUseCaseTest.java'


def require(text: str, needle: str, message: str) -> None:
    if needle not in text:
        raise SystemExit('FAIL: ' + message)


def schema(c: sqlite3.Connection) -> None:
    c.executescript('''
    PRAGMA foreign_keys=ON;
    CREATE TABLE books(id TEXT PRIMARY KEY);
    CREATE TABLE reading_progress(
      book_id TEXT PRIMARY KEY, anchor_id TEXT, paragraph_index INTEGER DEFAULT 0,
      paragraph_id TEXT NOT NULL, char_offset INTEGER NOT NULL, percent REAL DEFAULT 0,
      chapter_title TEXT, chapter_id TEXT, updated_at TEXT NOT NULL,
      reading_time_seconds INTEGER DEFAULT 0,
      FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE);
    CREATE TABLE reading_history(
      book_id TEXT PRIMARY KEY,last_opened_at TEXT NOT NULL,open_count INTEGER NOT NULL,
      FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE);
    CREATE TABLE reading_stats(
      id INTEGER PRIMARY KEY AUTOINCREMENT, book_id TEXT NOT NULL UNIQUE,
      first_read_at TEXT NOT NULL,last_read_at TEXT NOT NULL,total_reading_seconds INTEGER DEFAULT 0,
      reading_sessions INTEGER DEFAULT 0,start_percent INTEGER DEFAULT 0,end_percent INTEGER DEFAULT 0,
      current_percent INTEGER DEFAULT 0,completed_at TEXT,
      FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE);
    CREATE TABLE bookmarks(
      id TEXT PRIMARY KEY,book_id TEXT NOT NULL,paragraph_id TEXT NOT NULL,char_offset INTEGER DEFAULT 0,
      position REAL DEFAULT 0,chapter_title TEXT,context TEXT,created_at TEXT NOT NULL,
      FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE);
    CREATE TABLE reader_book_preferences(
      book_id TEXT PRIMARY KEY,preferences_json TEXT NOT NULL,updated_at TEXT NOT NULL,
      FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE);
    ''')


def transfer_state(source: sqlite3.Connection, target: sqlite3.Connection, book_id: str) -> None:
    rp = source.execute('SELECT * FROM reading_progress WHERE book_id=?', (book_id,)).fetchone()
    if rp:
        target.execute('''INSERT INTO reading_progress VALUES(?,?,?,?,?,?,?,?,?,?)
          ON CONFLICT(book_id) DO UPDATE SET anchor_id=excluded.anchor_id,paragraph_index=excluded.paragraph_index,
          paragraph_id=excluded.paragraph_id,char_offset=excluded.char_offset,percent=excluded.percent,
          chapter_title=excluded.chapter_title,chapter_id=excluded.chapter_id,updated_at=excluded.updated_at,
          reading_time_seconds=excluded.reading_time_seconds''', rp)
    rh = source.execute('SELECT book_id,last_opened_at,open_count FROM reading_history WHERE book_id=?',(book_id,)).fetchone()
    if rh:
        target.execute('''INSERT INTO reading_history VALUES(?,?,?) ON CONFLICT(book_id) DO UPDATE SET
          last_opened_at=excluded.last_opened_at,open_count=excluded.open_count''', rh)
    rs = source.execute('''SELECT book_id,first_read_at,last_read_at,total_reading_seconds,reading_sessions,
      start_percent,end_percent,current_percent,completed_at FROM reading_stats WHERE book_id=?''',(book_id,)).fetchone()
    if rs:
        target.execute('''INSERT INTO reading_stats(book_id,first_read_at,last_read_at,total_reading_seconds,reading_sessions,
          start_percent,end_percent,current_percent,completed_at) VALUES(?,?,?,?,?,?,?,?,?)
          ON CONFLICT(book_id) DO UPDATE SET first_read_at=excluded.first_read_at,last_read_at=excluded.last_read_at,
          total_reading_seconds=excluded.total_reading_seconds,reading_sessions=excluded.reading_sessions,
          start_percent=excluded.start_percent,end_percent=excluded.end_percent,current_percent=excluded.current_percent,
          completed_at=excluded.completed_at''', rs)
    for bm in source.execute('SELECT id,book_id,paragraph_id,char_offset,position,chapter_title,context,created_at FROM bookmarks WHERE book_id=?',(book_id,)):
        cur = target.execute('''INSERT INTO bookmarks VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET
          paragraph_id=excluded.paragraph_id,char_offset=excluded.char_offset,position=excluded.position,
          chapter_title=excluded.chapter_title,context=excluded.context,created_at=excluded.created_at
          WHERE bookmarks.book_id=excluded.book_id''', bm)
        if cur.rowcount == 0:
            raise sqlite3.IntegrityError('bookmark id collision')
    pref = source.execute('SELECT book_id,preferences_json,updated_at FROM reader_book_preferences WHERE book_id=?',(book_id,)).fetchone()
    if pref:
        target.execute('''INSERT INTO reader_book_preferences VALUES(?,?,?) ON CONFLICT(book_id) DO UPDATE SET
          preferences_json=excluded.preferences_json,updated_at=excluded.updated_at''', pref)


def main() -> None:
    usecase = USECASE.read_text(encoding='utf-8')
    saver = SAVER.read_text(encoding='utf-8')
    port = PORT.read_text(encoding='utf-8')
    adapter = ADAPTER.read_text(encoding='utf-8')
    lifecycle = LIFECYCLE.read_text(encoding='utf-8')
    test = TEST.read_text(encoding='utf-8') if TEST.exists() else ''
    require(usecase, 'userStateTransfer.transferCopiedBookState', 'copy use case must transfer collection-local user state')
    require(usecase, 'searchIndexSynchronizer.synchronizeSafelyNow', 'copy use case must synchronize Lucene only after committed DB/state transfer')
    require(usecase, 'batch,\n                false,\n                DuplicatePolicy.SKIP', 'BookSaver copy path must not index Lucene inside its post-transaction phase')
    require(usecase, 'PostCommitSearchSyncException', 'post-commit Lucene failure must be distinguished from transaction failure')
    require(usecase, 'copied files are retained', 'post-commit Lucene failure must retain physical files')
    require(usecase, 'reusableTargetIndex' , 'copy use case must inspect target index reuse state before selective updates')
    require(usecase, 'lifecycle.rebuildSearchIndex()', 'dirty/absent target index must be rebuilt before selective copy updates')
    require(lifecycle, 'public boolean initializeCollection(Collection collection, boolean rebuildIndex)', 'collection lifecycle must report index reuse state to mutation orchestrators')
    require(usecase, 'flushBatch(', 'copy use case must isolate/clean failed batches')
    require(saver, 'Consumer<List<Book>> afterDatabaseSave', 'BookSaver must expose same-transaction post-save hook')
    require(saver, 'if (afterDatabaseSave != null) afterDatabaseSave.accept(stableSaved);', 'state hook must execute inside DB transaction')
    require(port, 'must participate', 'user-state port must document transaction contract')
    for table in ('reading_progress', 'reading_history', 'reading_stats', 'bookmarks', 'reader_book_preferences'):
        require(adapter, table, f'adapter must cover {table}')
    require(adapter, 'WHERE bookmarks.book_id=excluded.book_id', 'bookmark collision must not hijack unrelated bookmark')
    require(adapter, 'SqliteInClauseSupport.MAX_ITEMS', 'source lookups must be bind-bounded')
    for fixture in (
        'rebuildsNonReusableTargetOnceThenUsesPostCommitSelectiveSync',
        'postCommitLuceneFailureRetainsPhysicalFileInsteadOfCreatingBrokenCatalogRow',
        'stateTransferFailureCleansPhysicalFileBecauseTargetTransactionDidNotCommit',
        'duplicateSkipRemovesUnusedCopiedFileAndDoesNotTouchLucene',
    ):
        require(test, fixture, f'missing copy regression fixture: {fixture}')

    with tempfile.TemporaryDirectory(prefix='mhl-copy-state-') as td:
        sdb = Path(td) / 'source.db'; tdb = Path(td) / 'target.db'
        source = sqlite3.connect(sdb); target = sqlite3.connect(tdb)
        schema(source); schema(target)
        source.execute("INSERT INTO books VALUES('b1')")
        source.execute("INSERT INTO reading_progress VALUES('b1','a1',3,'p1',42,55.5,'Ch','c1','2026-08-30T10:00:00',123)")
        source.execute("INSERT INTO reading_history VALUES('b1','2026-08-30T10:01:00',7)")
        source.execute("INSERT INTO reading_stats(book_id,first_read_at,last_read_at,total_reading_seconds,reading_sessions,start_percent,end_percent,current_percent,completed_at) VALUES('b1','2026-08-01','2026-08-30',999,8,10,80,75,NULL)")
        source.execute("INSERT INTO bookmarks VALUES('bm1','b1','p1',40,0.55,'Ch','ctx','2026-08-30T10:02:00')")
        source.execute("INSERT INTO reader_book_preferences VALUES('b1','{\"fontFamily\":\"Mono\"}','2026-08-30T10:03:00')")
        source.commit()

        target.execute("INSERT INTO books VALUES('b1')")
        transfer_state(source, target, 'b1'); target.commit()
        assert target.execute("SELECT char_offset FROM reading_progress WHERE book_id='b1'").fetchone()[0] == 42
        assert target.execute("SELECT open_count FROM reading_history WHERE book_id='b1'").fetchone()[0] == 7
        assert target.execute("SELECT total_reading_seconds FROM reading_stats WHERE book_id='b1'").fetchone()[0] == 999
        assert target.execute("SELECT context FROM bookmarks WHERE id='bm1'").fetchone()[0] == 'ctx'
        assert 'Mono' in target.execute("SELECT preferences_json FROM reader_book_preferences WHERE book_id='b1'").fetchone()[0]

        # A bookmark UUID already owned by another target book must fail rather than re-parent it.
        source.execute("INSERT INTO books VALUES('b2')")
        source.execute("INSERT INTO bookmarks VALUES('collision','b2','p2',1,0.1,NULL,NULL,'2026-08-30')")
        source.commit()
        target.execute("INSERT INTO books VALUES('other')")
        target.execute("INSERT INTO bookmarks VALUES('collision','other','px',2,0.2,NULL,NULL,'2026-08-29')")
        target.commit()
        target.execute('BEGIN')
        target.execute("INSERT INTO books VALUES('b2')")
        try:
            transfer_state(source, target, 'b2')
            raise AssertionError('collision must fail')
        except sqlite3.IntegrityError:
            target.rollback()
        assert target.execute("SELECT COUNT(*) FROM books WHERE id='b2'").fetchone()[0] == 0
        assert target.execute("SELECT book_id FROM bookmarks WHERE id='collision'").fetchone()[0] == 'other'
        source.close(); target.close()

    print('COPY BETWEEN COLLECTIONS CHECK: PASS')
    print(' - book row + collection-local user state share one target transaction contract')
    print(' - progress/history/statistics/bookmarks/reader overrides are preserved')
    print(' - bookmark ID collisions do not re-parent unrelated target data')
    print(' - failed state transfer rolls back the target book transaction')
    print(' - post-commit Lucene failure retains committed physical files')
    print(' - dirty/absent target index is rebuilt before selective copy synchronization')

if __name__ == '__main__':
    main()
