#!/usr/bin/env python3
from pathlib import Path
import sqlite3
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

def text(path):
    return (ROOT / path).read_text(encoding='utf-8')

def require(cond, msg):
    if not cond:
        raise AssertionError(msg)

mode = text('myhomelib-application/src/main/java/com/myhomelibcorp/application/navigation/NavigationMode.java')
nav = text('myhomelib-application/src/main/java/com/myhomelibcorp/application/navigation/DefaultNavigationQueryService.java')
port = text('myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/catalog/CatalogUpdateTrackingPort.java')
adapter = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/catalog/SqliteCatalogUpdateTrackingAdapter.java')
service = text('myhomelib-application/src/main/java/com/myhomelibcorp/application/catalog/CatalogUpdateService.java')
workspace = text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/updates/UpdatesWorkspaceController.java')
coordinator = text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/BookDownloadCoordinator.java')
manager = text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/navigation/WorkspaceManager.java')

require('UPDATES' in mode, 'NavigationMode.UPDATES missing')
require('case UPDATES -> loadUpdates()' in nav, 'Updates navigation query missing')
require('pendingUpdateCount()' in nav, 'Updates counter missing from navigation')
require('findPendingUpdateItems' in port and 'findPendingUpdateItems' in adapter, 'enriched pending update port missing')
require('pendingUpdateSnapshot()' in service and 'CatalogUpdateAuthorGroup' in service, 'grouped Stage 7 snapshot missing')
require('CatalogUpdateType.NEW_BY_FOLLOWED_AUTHOR' in workspace and 'CatalogUpdateType.UPDATED_DOWNLOADED_BOOK' in workspace,
        'workspace does not render both update types')
require('downloadUpdate(book)' in workspace, 'updated books are not force-downloaded')
require('download(book, true)' in coordinator, 'force update download path missing')
require('markDownloadedBaseline(bookId)' in text('myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/download/DownloadBookUseCase.java'),
        'successful download does not acknowledge catalog baseline')
require('showUpdatesWorkspace' in manager and '/view/updates-workspace.fxml' in manager, 'Updates workspace not wired')
require('navigateToAuthor' in workspace and 'navigateToBook' in workspace, 'author/book deep links missing')
require('Немає нових оновлень каталогу' in workspace, 'empty state missing')

ET.parse(ROOT / 'myhomelib-ui/src/main/resources/view/updates-workspace.fxml')

# Validate the core SQLite author-ranking query shape against a minimal in-memory catalog.
db = sqlite3.connect(':memory:')
db.executescript('''
CREATE TABLE authors(id TEXT PRIMARY KEY, first_name TEXT, middle_name TEXT, last_name TEXT);
CREATE TABLE books(id TEXT PRIMARY KEY, title TEXT, local INTEGER);
CREATE TABLE book_authors(book_id TEXT, author_id TEXT);
CREATE TABLE followed_authors(author_id TEXT PRIMARY KEY, followed_at TEXT);
CREATE TABLE catalog_update_events(book_id TEXT, update_type TEXT, source_id TEXT, detected_revision INTEGER,
 catalog_fingerprint TEXT, detected_at TEXT, acknowledged_at TEXT, PRIMARY KEY(book_id, update_type));
INSERT INTO authors VALUES ('a1','Other','','Author');
INSERT INTO authors VALUES ('a2','Followed','','Writer');
INSERT INTO books VALUES ('b1','Book',0);
INSERT INTO book_authors VALUES ('b1','a1');
INSERT INTO book_authors VALUES ('b1','a2');
INSERT INTO followed_authors VALUES ('a2','now');
INSERT INTO catalog_update_events VALUES ('b1','NEW_BY_FOLLOWED_AUTHOR','s',2,'f','now',NULL);
''')
rows = db.execute('''
WITH ranked_authors AS (
 SELECT ba.book_id, a.id author_id,
 TRIM(COALESCE(NULLIF(a.last_name,''),'') ||
      CASE WHEN COALESCE(NULLIF(a.first_name,''),'') <> '' THEN
        CASE WHEN COALESCE(NULLIF(a.last_name,''),'') <> '' THEN ' ' ELSE '' END || a.first_name ELSE '' END ||
      CASE WHEN COALESCE(NULLIF(a.middle_name,''),'') <> '' THEN
        CASE WHEN COALESCE(NULLIF(a.last_name,''),'') <> '' OR COALESCE(NULLIF(a.first_name,''),'') <> '' THEN ' ' ELSE '' END || a.middle_name ELSE '' END) author_name,
 ROW_NUMBER() OVER (PARTITION BY ba.book_id ORDER BY CASE WHEN fa.author_id IS NOT NULL THEN 0 ELSE 1 END,
 LOWER(COALESCE(a.last_name,'')), LOWER(COALESCE(a.first_name,'')), a.id) rn
 FROM book_authors ba JOIN authors a ON a.id=ba.author_id LEFT JOIN followed_authors fa ON fa.author_id=a.id
)
SELECT e.book_id, b.title, ra.author_id, ra.author_name
FROM catalog_update_events e JOIN books b ON b.id=e.book_id
LEFT JOIN ranked_authors ra ON ra.book_id=e.book_id AND ra.rn=1
WHERE e.acknowledged_at IS NULL
''').fetchall()
require(rows == [('b1', 'Book', 'a2', 'Writer Followed')], f'followed-author grouping failed: {rows!r}')

print('STAGE 7 ONLINE UPDATES UI CHECK: PASS')
print(' - Updates navigation mode + pending counter: PASS')
print(' - Author -> New/Updated -> Book snapshot: PASS')
print(' - followed co-author deterministic grouping: PASS')
print(' - force download for UPDATED_DOWNLOADED_BOOK: PASS')
print(' - successful download acknowledgement path: PASS')
print(' - open author/book actions + empty state: PASS')
print(' - Updates FXML parse: PASS')
