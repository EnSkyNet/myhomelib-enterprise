# Stage 25C Validation — Search / Sync Refactor

## Result

**PASS for all available offline/static/regression checks.**

## Stage 25C structural checks

- `LuceneSearchService`: **391 lines** (662 before Stage 25C).
- `FolderSyncService`: **339 lines** (522 before Stage 25C).
- Extracted components:
  - `LuceneDocumentMapper`;
  - `LuceneUnifiedFilterBuilder`;
  - `LuceneQueryNormalizer`;
  - `FolderSyncBookSupport`.
- Classic query normalizer standalone `javac` + runtime smoke: PASS.
- Scanner failure double-count regression fixture: PRESENT.

## Final roadmap regression sweep

PASS:

- Stage 3 through Stage 25C guards;
- Stage 8/9 SQL/Lucene unified-filter guard after extraction;
- Stage 19/20 Reader portable smoke;
- Stage 17/18 OPDS loopback HTTP smoke;
- Stage 24 stored performance guardrails;
- `large-library-pre-stage7-check.py`;
- `architecture-check.py` — module graph intact; UI debt ratchet remains 18/18 output-port users and 28/28 non-value domain-model users;
- `static_release_check.py` — 38 XML documents, 25 FXML files / 161 handler references, 33 SQLite migrations, 648 production Java sources / 63 test sources;
- language catalogues `uk/en/bg` — schema v2, 200 UI keys and 110 genre keys each.

## Large-library/performance contract

No Stage-25C change reintroduces eager author materialization or changes the Stage-24 query/index contract. The stored 100k/500k/1M performance baseline remains the regression reference.

## Maven/JUnit limitation in this container

`./mvnw` still cannot download Maven 3.9.16 because DNS/network access to `repo.maven.apache.org` is unavailable. Maven/JUnit execution is therefore **not** reported as PASS here. The cross-platform Stage-23 CI workflow remains the authoritative clean-build/runtime packaging gate.
