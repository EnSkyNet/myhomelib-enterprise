#!/usr/bin/env python3
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
mapper = (root / 'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/search/LuceneDocumentMapper.java').read_text()
executor = (root / 'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/search/LuceneSearchExecutor.java').read_text()
service = (root / 'myhomelib-application/src/main/java/com/myhomelibcorp/application/search/SearchService.java').read_text()
workspace = (root / 'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/search/SearchWorkspaceController.java').read_text()
query_factory = (root / 'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/search/SearchQueryFactory.java').read_text()

failures = []
stored_yes = re.findall(r'(?:TextField|StringField)\("([^"]+)"[^\n]*Field\.Store\.YES', mapper)
if stored_yes != ['id']:
    failures.append(f'expected only id as stored field, got {stored_yes}')
if 'searcher.count(query)' not in executor:
    failures.append('exact total-hit count is missing')
if 'searcher.searchAfter(' not in executor or 'SKIP_BATCH_SIZE' not in executor:
    failures.append('bounded searchAfter deep paging is missing')
if 'Math.min(100_000, offset + limit)' in executor:
    failures.append('legacy 100k offset cap returned')
if 'ids.stream().map(byId::get)' not in service:
    failures.append('Lucene result order is not restored after repository IN lookup')
if 'clauses.add("library_rate:["' not in query_factory or 'clauses.add("rate:["' in query_factory:
    failures.append('saved advanced-search library rating is not aligned with live library_rate filter')

if failures:
    for failure in failures:
        print('FAIL:', failure)
    raise SystemExit(1)
print('PASS: Lucene stored-field, exact-hit, deep-page, result-order and saved-filter invariants')
