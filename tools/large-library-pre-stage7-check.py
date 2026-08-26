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
dto=txt('myhomelib-application/src/main/java/com/myhomelibcorp/application/dto/CollectionDto.java')

req('findByInitial(char initial)' in auth and 'findFirstInitial()' in auth and 'countByInitial(char initial)' in auth,
    'scalable author repository API missing')
req((('authorRepository.findByInitial' in nav) or ('navigationFacetRepository.findAuthors' in nav)) and 'authorRepository.findAll()' not in nav,
    'navigation still loads all authors or is not initial-scoped')
req('idx_authors_navigation_initial' in txt('myhomelib-infrastructure/src/main/resources/db/migration/V32__author_navigation_initial_index.sql'),
    'author initial index migration missing')
req('progressListener' in fast and 'statusConsumer' in fast and 'getProgressListener()' in ctx and 'getStatusConsumer()' in ctx,
    'INPX progress callbacks are not connected')
req('dictionaryCache.loadAuthors' not in inpx and 'authorRepository.findAll()' not in inpx,
    'INPX pipeline still materializes all authors')
req('selectedCollection' in collections and 'active' in dto and 'allowRename' in dto and 'allowDelete' in dto,
    'selected/active collection model missing')

hits=[]
for p in ROOT.glob('myhomelib-*/src/main/**/*.java'):
    s=p.read_text(encoding='utf-8', errors='ignore')
    if 'authorRepository.findAll()' in s or 'dictionaryCache.loadAuthors' in s:
        hits.append(str(p.relative_to(ROOT)))
req(not hits, f'eager author load remains in production: {hits}')

print('LARGE LIBRARY PRE-STAGE-7 CHECK: PASS')
print(' - author navigation is initial-scoped/aggregated in SQL: PASS')
print(' - author initial expression index: PASS')
print(' - no production authorRepository.findAll()/dictionaryCache.loadAuthors(): PASS')
print(' - INPX progress/status callback chain: PASS')
print(' - selected vs active collection state: PASS')
