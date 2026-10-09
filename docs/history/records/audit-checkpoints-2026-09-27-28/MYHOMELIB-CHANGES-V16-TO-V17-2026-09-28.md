# MyHomeLib — Changes V16 → V17

Date: 2026-09-28  
Baseline: V16 clean checkpoint.

## Production changes

### Modified

`myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/InpxImportPipeline.java`

- removed embedded implementation of author-cache sizing, Bloom filter hashing, preload thresholds and preload mechanics;
- retained thin private compatibility delegates used by existing characterization tests;
- import transaction, catalog synchronization, persistence and progress behavior are unchanged;
- line count reduced from 1124 to 1047.

### Added

`myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/InpxAuthorCacheSupport.java`

Package-private helper that owns:

- bounded author LRU cache creation;
- full/delta preload policy;
- safe bounded author-table preload;
- Bloom-filter seen-author tracking.

No source/test file was deleted.

## Verification

Before extraction:
- targeted INPX/import/Stage6 characterization: **31/31 PASS**.

After extraction:
- same targeted characterization: **31/31 PASS**;
- final full reactor `clean verify`: **1789 tests, 0 failures, 0 errors, 17 skipped; 20/20 modules SUCCESS**;
- infrastructure: **637 tests, 0 failures, 0 errors, 9 skipped**;
- production package: **BUILD SUCCESS**;
- Linux jpackage app-image: **PASS**;
- packaged launcher smoke: **PASS**;
- Stage23: **PASS**;
- static release check: **PASS**;
- standalone architecture ratchet: unchanged one known debt group.

## Maven version caveat

All final V17 Maven evidence was produced with the supplied offline Maven **3.9.6**. The wrapper contract requires **3.9.16**, so exact-version acceptance remains open.
