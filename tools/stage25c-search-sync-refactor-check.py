#!/usr/bin/env python3
"""Offline Stage 25C search/sync targeted-refactor guard."""
from pathlib import Path
import subprocess, tempfile, textwrap, shutil, sys
ROOT=Path(__file__).resolve().parents[1]
errors=[]
def need(c,m):
    if not c: errors.append(m)
def text(rel):
    p=ROOT/rel
    if not p.exists(): errors.append(f'missing {rel}'); return ''
    return p.read_text(encoding='utf-8')

lucene=text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/search/LuceneSearchService.java')
normalizer=text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/search/LuceneQueryNormalizer.java')
filters=text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/search/LuceneUnifiedFilterBuilder.java')
docmap=text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/search/LuceneDocumentMapper.java')
sync=text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/sync/FolderSyncService.java')
support=text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/sync/FolderSyncBookSupport.java')

need(len(lucene.splitlines()) <= 420, f'LuceneSearchService still too large: {len(lucene.splitlines())}')
need(len(sync.splitlines()) <= 360, f'FolderSyncService still too large: {len(sync.splitlines())}')
for marker in ('LuceneDocumentMapper documentMapper','LuceneUnifiedFilterBuilder unifiedFilterBuilder','LuceneQueryNormalizer queryNormalizer'):
    need(marker in lucene, f'Lucene orchestration missing {marker}')
need('createDocument(BookSnapshot' not in lucene and 'normalizeClassicSearchSyntax(String raw)' not in lucene,
     'LuceneSearchService still owns extracted mapping/query-normalization mechanics')
for marker in ('case \">=\"','authors:','normalizePercentWildcards'):
    need(marker in normalizer, f'LuceneQueryNormalizer missing compatibility marker {marker}')
for marker in ('WildcardQuery','BookFilterSpec','rate_num','read'):
    need(marker in filters, f'LuceneUnifiedFilterBuilder missing {marker}')
for marker in ('created_day','lib_id','detectFormat','deleted'):
    need(marker in docmap, f'LuceneDocumentMapper missing {marker}')
need('FolderSyncBookSupport syncSupport' in sync, 'FolderSyncService helper extraction missing')
for old in ('private Book normalizeStorage(', 'private Book mergePreservingUserState(', 'private Path physicalPath('):
    need(old not in sync, f'FolderSyncService still owns extracted policy: {old}')
for marker in ('normalizeStorage(', 'mergePreservingUserState(', 'physicalPath(', 'archiveChanged(', 'isArchive('):
    need(marker in support, f'FolderSyncBookSupport missing {marker}')
need('counters.errors++;\n            counters.errors++;' not in sync, 'scanner failure is still double-counted')
need('scannerFailureIsCountedOnce' in text('myhomelib-infrastructure/src/test/java/com/myhomelibcorp/infrastructure/sync/FolderSyncServiceTest.java'),
     'scanner error-count regression test missing')
need((ROOT/'myhomelib-infrastructure/src/test/java/com/myhomelibcorp/infrastructure/search/LuceneQueryNormalizerTest.java').is_file(),
     'Lucene query-normalizer regression test missing')
need((ROOT/'docs/performance-baseline.json').is_file(), 'Stage 24 baseline missing before Stage 25C refactor')

# Syntax + behaviour smoke for the extracted classic-query normalizer using tiny Lucene stubs.
def normalizer_smoke():
    with tempfile.TemporaryDirectory(prefix='mhl-25c-query-') as td:
        td=Path(td); src=td/'src'; out=td/'out'; out.mkdir()
        rel='myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/search/LuceneQueryNormalizer.java'
        dest=src/'com/myhomelibcorp/infrastructure/search/LuceneQueryNormalizer.java'; dest.parent.mkdir(parents=True); shutil.copy(ROOT/rel,dest)
        sm=src/'com/myhomelibcorp/application/query/search/SearchMode.java'; sm.parent.mkdir(parents=True); shutil.copy(ROOT/'myhomelib-application/src/main/java/com/myhomelibcorp/application/query/search/SearchMode.java',sm)
        q=src/'org/apache/lucene/search/Query.java'; q.parent.mkdir(parents=True); q.write_text('package org.apache.lucene.search; public class Query {}',encoding='utf-8')
        qp=src/'org/apache/lucene/queryparser/classic/QueryParser.java'; qp.parent.mkdir(parents=True); qp.write_text('package org.apache.lucene.queryparser.classic; import org.apache.lucene.search.Query; public class QueryParser { public Query parse(String s){return new Query();} public static String escape(String s){return s;} }',encoding='utf-8')
        smoke=src/'com/myhomelibcorp/infrastructure/search/Smoke.java'; smoke.parent.mkdir(parents=True,exist_ok=True)
        smoke.write_text(textwrap.dedent('''
          package com.myhomelibcorp.infrastructure.search;
          public class Smoke { public static void main(String[] a){ var n=new LuceneQueryNormalizer(new org.apache.lucene.queryparser.classic.QueryParser()); if(!"authors:Франко".equals(n.normalizeClassicSearchSyntax("автор:Франко"))) throw new AssertionError(); if(!"year:[2023 TO *]".equals(n.normalizeClassicSearchSyntax("year>=2023"))) throw new AssertionError(n.normalizeClassicSearchSyntax("year>=2023")); if(!"*істор*".equals(n.normalizeClassicSearchSyntax("%істор%"))) throw new AssertionError(); }}
        '''),encoding='utf-8')
        cp=subprocess.run(['javac','--release','21','-d',str(out),*[str(p) for p in src.rglob('*.java')]],capture_output=True,text=True,timeout=30)
        if cp.returncode: raise RuntimeError('normalizer javac failed:\n'+cp.stdout+cp.stderr)
        run=subprocess.run(['java','-cp',str(out),'com.myhomelibcorp.infrastructure.search.Smoke'],capture_output=True,text=True,timeout=30)
        if run.returncode: raise RuntimeError('normalizer smoke failed:\n'+run.stdout+run.stderr)

if errors:
    print('STAGE 25C SEARCH/SYNC REFACTOR CHECK: FAIL')
    for e in errors: print(' -',e)
    sys.exit(1)
try: normalizer_smoke()
except Exception as e:
    print('STAGE 25C SEARCH/SYNC REFACTOR CHECK: FAIL'); print(e); sys.exit(1)
print('STAGE 25C SEARCH/SYNC REFACTOR CHECK: PASS')
print(f' - LuceneSearchService: {len(lucene.splitlines())} lines; mapping/filter/query normalization extracted')
print(f' - FolderSyncService: {len(sync.splitlines())} lines; storage/path/user-state merge policy extracted')
print(' - classic query normalizer standalone javac/runtime smoke: PASS')
print(' - scanner IOException counted once: regression fixture PRESENT')
