#!/usr/bin/env python3
"""Regression guard for MCP catalog search query shape at large-library scale."""
from pathlib import Path
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
src = (ROOT / 'myhomelib-mcp/src/main/java/com/myhomelibcorp/mcp/LibraryDb.java').read_text(encoding='utf-8')

start = src.index('ArrayNode searchBooks(')
end = src.index('\n    ArrayNode listAuthors(', start)
body = src[start:end]
errors = []

if 'LEFT JOIN book_authors' in body or 'LEFT JOIN book_genres' in body:
    errors.append('searchBooks must not expand the full catalog through outer author/genre LEFT JOINs')
if re.search(r'GROUP BY\s+b\.id', body, re.I):
    errors.append('searchBooks must not GROUP BY the full books result before LIMIT')
if body.count('EXISTS (') < 2:
    errors.append('author and genre search predicates must use bounded EXISTS probes')
if "WHERE ba.book_id=b.id" not in body or "WHERE bg.book_id=b.id" not in body:
    errors.append('author/genre enrichment must be correlated to selected books')
if 'ORDER BY lower(b.title) LIMIT ? OFFSET ?' not in body:
    errors.append('catalog ordering must use the existing idx_books_title_lower expression index')

# Deterministic EXPLAIN guard: blank catalog browsing must be index-ordered and page-first.
con = sqlite3.connect(':memory:')
con.executescript('''
CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT,series TEXT,sequence_number INTEGER,language TEXT,year INTEGER,
 publisher TEXT,lib_id TEXT,library_rate INTEGER,rate INTEGER,progress INTEGER,file_name TEXT,folder TEXT,
 archive_entry TEXT,collection_root TEXT,local INTEGER,annotation TEXT,keywords TEXT,deleted INTEGER DEFAULT 0);
CREATE TABLE authors(id TEXT PRIMARY KEY,last_name TEXT,first_name TEXT,middle_name TEXT);
CREATE TABLE book_authors(book_id TEXT,author_id TEXT,PRIMARY KEY(book_id,author_id));
CREATE TABLE genres(code TEXT PRIMARY KEY,name TEXT);
CREATE TABLE book_genres(book_id TEXT,genre_code TEXT,PRIMARY KEY(book_id,genre_code));
CREATE INDEX idx_books_title_lower ON books(lower(title));
''')
query = '''
SELECT b.id,b.title,b.series,b.sequence_number,b.language,b.year,b.publisher,b.lib_id,
       b.library_rate,b.rate,b.progress,b.file_name,b.folder,b.archive_entry,b.collection_root,b.local,
       COALESCE((SELECT GROUP_CONCAT(DISTINCT TRIM(a.last_name || ' ' || a.first_name || ' ' || a.middle_name))
                 FROM book_authors ba JOIN authors a ON a.id=ba.author_id WHERE ba.book_id=b.id), '') authors,
       COALESCE((SELECT GROUP_CONCAT(DISTINCT g.name)
                 FROM book_genres bg JOIN genres g ON g.code=bg.genre_code WHERE bg.book_id=b.id), '') genres
FROM books b
WHERE b.deleted=0 AND (?='' OR b.title LIKE ? ESCAPE '\\' OR b.series LIKE ? ESCAPE '\\'
 OR b.annotation LIKE ? ESCAPE '\\' OR b.keywords LIKE ? ESCAPE '\\' OR b.file_name LIKE ? ESCAPE '\\'
 OR b.publisher LIKE ? ESCAPE '\\'
 OR EXISTS (SELECT 1 FROM book_authors ba JOIN authors a ON a.id=ba.author_id
            WHERE ba.book_id=b.id AND (a.last_name LIKE ? ESCAPE '\\' OR a.first_name LIKE ? ESCAPE '\\'))
 OR EXISTS (SELECT 1 FROM book_genres bg JOIN genres g ON g.code=bg.genre_code
            WHERE bg.book_id=b.id AND g.name LIKE ? ESCAPE '\\'))
ORDER BY lower(b.title) LIMIT ? OFFSET ?
'''
params = [''] + ['%%'] * 9 + [50, 0]
plan = '\n'.join(str(r) for r in con.execute('EXPLAIN QUERY PLAN ' + query, params))
if 'idx_books_title_lower' not in plan:
    errors.append('EXPLAIN does not use idx_books_title_lower for blank catalog browsing')
if 'USE TEMP B-TREE FOR ORDER BY' in plan:
    errors.append('blank catalog browsing unexpectedly materializes a temp ORDER BY B-tree')

# Semantic fixture: ensure enrichment and author/genre matching still work.
con.executemany('INSERT INTO books(id,title,series,language,lib_id,file_name,local,deleted) VALUES(?,?,?,?,?,?,?,0)', [
    ('b1','Zulu','Saga','uk','L1','z.fb2',1), ('b2','alpha','Cycle','en','L2','a.fb2',1),
])
con.execute("INSERT INTO authors VALUES('a1','Шевченко','Тарас','')")
con.execute("INSERT INTO book_authors VALUES('b1','a1')")
con.execute("INSERT INTO genres VALUES('g1','Поезія')")
con.execute("INSERT INTO book_genres VALUES('b1','g1')")

def run(term):
    like = '%' + term + '%'
    return list(con.execute(query, [term] + [like] * 9 + [50, 0]))
blank = run('')
if [r[0] for r in blank] != ['b2','b1']:
    errors.append(f'blank search ordering changed unexpectedly: {[r[0] for r in blank]}')
by_author = run('Шевченко')
if len(by_author) != 1 or by_author[0][0] != 'b1' or 'Шевченко' not in by_author[0][-2]:
    errors.append('author EXISTS/enrichment semantics regressed')
by_genre = run('Поезія')
if len(by_genre) != 1 or by_genre[0][0] != 'b1' or 'Поезія' not in by_genre[0][-1]:
    errors.append('genre EXISTS/enrichment semantics regressed')

if errors:
    print('MCP SEARCH SCALE CHECK: FAIL')
    for e in errors: print(' -', e)
    raise SystemExit(1)
print('MCP SEARCH SCALE CHECK: PASS')
print(' - page-first author/genre enrichment')
print(' - author/genre predicates use EXISTS')
print(' - blank browse uses idx_books_title_lower without temp ORDER BY')
print(' - semantic fixture preserves author/genre matches')
