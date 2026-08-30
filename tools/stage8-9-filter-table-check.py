#!/usr/bin/env python3
from pathlib import Path
import json, re, sqlite3, xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
def text(p): return (ROOT / p).read_text(encoding='utf-8')
def require(cond, msg):
    if not cond: raise AssertionError(msg)

spec = text('myhomelib-application/src/main/java/com/myhomelibcorp/application/filter/BookFilterSpec.java')
state = text('myhomelib-application/src/main/java/com/myhomelibcorp/application/filter/BookFilterStateService.java')
adapter = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/helper/BookFilterSqlAdapter.java')
query_builder = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/helper/BookQueryBuilder.java')
facets = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteNavigationFacetRepository.java')
lucene = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/search/LuceneSearchService.java')
lucene_executor = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/search/LuceneSearchExecutor.java')
lucene_filter = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/search/LuceneUnifiedFilterBuilder.java')
lucene_doc = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/search/LuceneDocumentMapper.java')
table = text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/table/BookTableController.java')
profiles = text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/table/TableProfileService.java')
profile_state = text('myhomelib-application/src/main/java/com/myhomelibcorp/application/table/TableProfileStateService.java')
workspace = text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/navigation/WorkspaceManager.java')
fast_writer = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/JdbcBatchWriter.java')
fast_pipeline = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/InpxImportPipeline.java')
normalizer = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/InpxBookNormalizer.java')
denormalized = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/helper/BookDenormalizedValues.java')
legacy = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/collection/legacy/SqliteLegacyCollectionAttachAdapter.java')

for token in ['language', 'yearFrom', 'yearTo', 'format', 'local', 'read', 'ratingMin', 'ratingMax', 'hideUnrated', 'quickField', 'quickValue']:
    require(token in spec, f'BookFilterSpec missing {token}')
require('filter.global.' in state and 'settings.put' in state, 'filter state is not persisted')
require('BookFilterSqlAdapter.build(filter, "b")' in query_builder, 'book SQL does not use shared filter adapter')
require(facets.count('BookFilterSqlAdapter.build(filter, "b")') >= 8, 'navigation facets do not share filter SQL')
require(('unifiedFilterBuilder.addTo' in lucene or 'filterBuilder.addTo' in lucene_executor) and 'BookFilterSpec' in lucene_filter, 'Lucene unified filter missing')
for field in ['rate_num','year_num','format','local','read']:
    require(field in (lucene_filter + lucene_doc), f'Lucene index/filter missing {field}')
require('escapeLike' in adapter and "ESCAPE '\\\\'" in adapter, 'SQL quick filter is not literal/escaped')
require('escapeWildcardTerm' in lucene_filter and 'WildcardQuery' in lucene_filter, 'Lucene quick filter is not safe substring matching')
require('setSortPolicy' in table and 'bookLoaderService.setSort' in table, 'table still sorts only loaded page')
require('TableProfileStateService' in profiles and 'stateService.load' in profiles and 'stateService.save' in profiles, 'UI table profile adapter not wired')
require('table.profile.' in profile_state and 'column.' in profile_state and 'sort.column' in profile_state, 'table profiles not persisted')
for profile in ['series','genre','year','language','archive','keyword','reviews','all-books','already-read','history']:
    require(f'loadBookTableWorkspace("{profile}")' in workspace, f'missing table profile {profile}')
require('format, author_sort' in fast_writer and 'BookDenormalizedValues.format' in fast_writer, 'fast INPX does not maintain denormalized format')
require('BookDenormalizedValues.authorSort(parsedAuthors.keys())' in normalizer and 'Collection<AuthorNameKey>' in denormalized, 'fast INPX does not derive author_sort from structured author identities')
require('authorSortFromKeys' not in fast_writer + fast_pipeline + normalizer, 'delimiter-serialized author identity helper was reintroduced')
require('refreshDenormalizedBookFields' in legacy, 'legacy HLC2 import does not maintain denormalized fields')

# All FXML must remain well-formed.
fxml_files = list((ROOT/'myhomelib-ui/src/main/resources/view').glob('*.fxml'))
for p in fxml_files: ET.parse(p)

# Language catalogs must stay valid and bundled/external copies consistent for Stage 8/9 keys.
for code in ('uk','en','bg'):
    a=json.loads((ROOT/f'Lang/{code}.json').read_text(encoding='utf-8'))
    b=json.loads((ROOT/f'myhomelib-ui/src/main/resources/lang/default/{code}.json').read_text(encoding='utf-8'))
    for key in ('Фільтри книг','Фільтр активний','Колонки таблиці','Швидкий фільтр:','Розмір сторінки:'):
        require(key in a['translations'] and key in b['translations'], f'{code} missing {key}')

# Apply every SQLite migration on a fresh database.
mig_dir=ROOT/'myhomelib-infrastructure/src/main/resources/db/migration'
migs=sorted(mig_dir.glob('V*.sql'), key=lambda p:int(re.match(r'V(\d+)',p.name).group(1)))
con=sqlite3.connect(':memory:')
for p in migs: con.executescript(p.read_text(encoding='utf-8'))
cols={r[1] for r in con.execute('PRAGMA table_info(books)')}
require({'format','author_sort'} <= cols, 'V18/V33 denormalized columns missing')
indexes={r[1] for r in con.execute("PRAGMA index_list('books')")}
require('idx_books_format' in indexes and 'idx_books_author_sort' in indexes, 'denormalized indexes missing')
con.close()

# V33 upgrade/backfill semantics from a V32-era database.
con=sqlite3.connect(':memory:')
for p in migs:
    if int(re.match(r'V(\d+)',p.name).group(1)) > 32: break
    con.executescript(p.read_text(encoding='utf-8'))
con.execute("INSERT INTO authors(id,first_name,middle_name,last_name) VALUES('a1','Ivan','','Abramov')")
con.execute("INSERT INTO books(id,title,file_name,deleted,format,author_sort) VALUES('b1','Alpha','alpha.fb2',0,NULL,NULL)")
con.execute("INSERT INTO book_authors(book_id,author_id) VALUES('b1','a1')")
con.executescript((mig_dir/'V33__maintain_book_format_author_sort.sql').read_text(encoding='utf-8'))
require(con.execute("SELECT format,author_sort FROM books WHERE id='b1'").fetchone()==('FB2','abramov ivan'), 'V33 backfill failed')
con.execute("UPDATE authors SET last_name='Zed' WHERE id='a1'")
require(con.execute("SELECT author_sort FROM books WHERE id='b1'").fetchone()[0]=='zed ivan', 'V33 author edit trigger failed')
con.close()

# Large-library invariant must still hold after navigation refactor.
hits=[]
for p in ROOT.glob('myhomelib-*/src/main/**/*.java'):
    s=p.read_text(encoding='utf-8',errors='ignore')
    if 'authorRepository.findAll()' in s or 'dictionaryCache.loadAuthors' in s: hits.append(str(p.relative_to(ROOT)))
require(not hits, f'eager author materialization reintroduced: {hits}')

print('STAGE 8+9 FILTER/TABLE CHECK: PASS')
print(f' - {len(migs)} SQLite migrations including V33: PASS')
print(f' - {len(fxml_files)} FXML files parsed: PASS')
print(' - one persisted BookFilterSpec across SQL navigation/table and Lucene: PASS')
print(' - safe quick-filter substring semantics: PASS')
print(' - per-workspace table profiles + server sorting: PASS')
print(' - fast INPX and legacy HLC2 maintain format/author_sort: PASS')
print(' - no eager author-table materialization: PASS')
