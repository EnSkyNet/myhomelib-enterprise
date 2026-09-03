#!/usr/bin/env python3
from pathlib import Path
import re
ROOT = Path(__file__).resolve().parents[1]

def txt(p): return (ROOT/p).read_text(encoding='utf-8')
def req(c,m):
    if not c: raise AssertionError(m)

nav=txt('myhomelib-application/src/main/java/com/myhomelibcorp/application/navigation/DefaultNavigationQueryService.java')
auth=txt('myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/repository/AuthorRepository.java')
sql=txt('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteAuthorRepository.java')
inpx=txt('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/InpxImportPipeline.java')
fast=txt('myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/importer/FastImportService.java')
ctx=txt('myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/imports/ImportFileUseCase.java')
collections=txt('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/collection/CollectionWorkspaceController.java')
nav_panel=txt('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/navigation/NavigationPanelController.java')
facet_sql=txt('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteNavigationFacetRepository.java')
dto=txt('myhomelib-application/src/main/java/com/myhomelibcorp/application/dto/CollectionDto.java')
search_request=txt('myhomelib-application/src/main/java/com/myhomelibcorp/application/query/search/SearchRequest.java')
search_service=txt('myhomelib-application/src/main/java/com/myhomelibcorp/application/search/SearchService.java')
lucene_exec=txt('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/search/LuceneSearchExecutor.java')
search_ui=txt('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/search/SearchWorkspaceController.java')
book_port=txt('myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/repository/BookQueryRepository.java')
book_sql=txt('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteBookQueryRepository.java')
book_cache=txt('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/cache/CachedBookQueryRepository.java')
search_service=txt('myhomelib-application/src/main/java/com/myhomelibcorp/application/search/SearchService.java')
book_loader=txt('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/BookLoaderService.java')
book_builder=txt('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/helper/BookQueryBuilder.java')

filter_adapter=txt('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/helper/BookFilterSqlAdapter.java')
req('normalizedLanguageExpression' in filter_adapter
    and 'BookFilterSqlAdapter.normalizedLanguageExpression("b")' in facet_sql,
    'language facet/filter no longer share the indexed normalization expression')

req('findByInitial(char initial)' in auth and 'findFirstInitial()' in auth and 'countByInitial(char initial)' in auth,
    'scalable author repository API missing')
req((('authorRepository.findByInitial' in nav) or ('navigationFacetRepository.findAuthors' in nav)) and 'authorRepository.findAll()' not in nav,
    'navigation still loads all authors or is not initial-scoped')
req('idx_authors_navigation_initial' in txt('myhomelib-infrastructure/src/main/resources/db/migration/V32__author_navigation_initial_index.sql'),
    'author initial index migration missing')
language_index = txt('myhomelib-infrastructure/src/main/resources/db/migration/V47__active_language_browse_index.sql')
req('idx_books_active_language_title' in language_index
    and "LOWER(TRIM(COALESCE(language, '')))" in language_index
    and 'WHERE deleted = 0' in language_index
    and "LOWER(TRIM(COALESCE(language, ''))) <> ''" in language_index,
    'active normalized-language browse index migration missing')
req('progressListener' in fast and 'statusConsumer' in fast and 'getProgressListener()' in ctx and 'getStatusConsumer()' in ctx,
    'INPX progress callbacks are not connected')
req('dictionaryCache.loadAuthors' not in inpx and 'authorRepository.findAll()' not in inpx,
    'INPX pipeline still materializes all authors')
req('selectedCollection' in collections and 'active' in dto and 'allowRename' in dto and 'allowDelete' in dto,
    'selected/active collection model missing')
req('loadAuthorsAfter' in nav and 'findAuthorsAfter' in facet_sql,
    'author navigation keyset API missing')
req('authorCursor' in nav_panel and 'loadAuthorsAfter' in nav_panel and 'loadAuthorsPage(' not in nav_panel,
    'NavigationPanel still uses OFFSET author paging')
keyset_block=facet_sql[facet_sql.index('public AuthorFacetSlice findAuthorsAfter') : facet_sql.index('private static String nullToEmpty')]
req('> (?, ?, ?, ?)' in keyset_block and 'safeLimit + 1' in keyset_block and 'OFFSET' not in keyset_block,
    'SQLite author continuation is not true keyset pagination')
req('OptionalLong.empty()' in keyset_block and 'queryForObject' not in keyset_block and 'COUNT(*) FROM (' not in keyset_block,
    'author navigation still performs an exact total count in the synchronous hot path')

req('boolean trackTotalHits' in search_request and '.trackTotalHits(false)' not in search_ui,
    'search request total-hit tracking contract missing or leaked into UI')
req('searchPage(SearchRequest request, int limit, int offset, long knownTotal)' in search_service,
    'interactive search continuation API with known total missing')
req('request.trackTotalHits() ? searcher.count(query) : -1' in lucene_exec,
    'Lucene continuation still repeats exact count(query)')
req('searchPage(requestSnapshot, BOOK_PAGE_SIZE, offset, activeBookTotal)' in search_ui,
    'Search Workspace continuation does not reuse the first-page total')

req('findListItemsByIds' in book_port and 'findListItemsByIds' in book_sql,
    'lightweight by-id list projection contract missing')
list_block=book_sql[book_sql.index('public List<Book> findListItemsByIds') : book_sql.index('public Optional<Book> findByStorage')]
req('BOOK_LIST_PROJECTION' in list_block and 'bookListRowMapper' in list_block and 'SELECT *' not in list_block,
    'search/list by-id path still selects the full books row')
req('bookQueryRepository.findListItemsByIds(ids)' in search_service,
    'SearchService still loads full book rows for result tables')
req('return delegate.findListItemsByIds(ids);' in book_cache and 'bookCache.put' not in book_cache[book_cache.index('public List<Book> findListItemsByIds'):book_cache.index('public Optional<Book> findByStorage')],
    'lightweight list items can pollute the full-book cache')

req('findPage(BookQuery query, long knownTotal)' in book_port
    and 'findTitlePageByCursor' in book_port,
    'book continuation contract does not reuse total/keyset cursor')
req('currentPageLastCursor' in book_loader and 'BookPageDirection.AFTER' in book_loader
    and 'currentPageFirstCursor' in book_loader and 'BookPageDirection.BEFORE' in book_loader
    and 'vm.getTotalElements()' in book_loader,
    'BookLoader continuation does not use bidirectional cursor + known total')
cursor_block=book_builder[book_builder.index('public SqlQuery buildTitleCursor'):book_builder.index('private void addJoins')]
req('(b.title, b.id)' in cursor_block and 'LIMIT ?' not in cursor_block
    and 'OFFSET' not in cursor_block,
    'title cursor builder contract regressed')
req('buildSelectSqlWithoutOffset' in book_builder
    and 'sql.append(" LIMIT ?")' in book_builder,
    'title cursor SQL no longer uses bounded no-OFFSET page')

hits=[]
for p in ROOT.glob('myhomelib-*/src/main/**/*.java'):
    s=p.read_text(encoding='utf-8', errors='ignore')
    if 'authorRepository.findAll()' in s or 'dictionaryCache.loadAuthors' in s:
        hits.append(str(p.relative_to(ROOT)))
req(not hits, f'eager author load remains in production: {hits}')

print('LARGE LIBRARY PRE-STAGE-7 CHECK: PASS')
print(' - author navigation is initial-scoped/aggregated in SQL: PASS')
print(' - author initial expression index: PASS')
print(' - normalized language facet/browse expression index: PASS')
print(' - no production authorRepository.findAll()/dictionaryCache.loadAuthors(): PASS')
print(' - INPX progress/status callback chain: PASS')
print(' - selected vs active collection state: PASS')
print(' - author navigation uses keyset cursor; exact COUNT is removed from the synchronous hot path: PASS')
print(' - search load-more reuses first-page total; Lucene count(query) is skipped on continuation: PASS')
print(' - search by-id rows use lightweight projection and bypass the full-book cache: PASS')
print(' - catalog continuation reuses known total and TITLE uses bidirectional keyset paging: PASS')
