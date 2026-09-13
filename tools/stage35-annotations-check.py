#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []
def need(condition, message):
    if not condition: errors.append(message)
def text(path): return (ROOT / path).read_text(encoding='utf-8')

anchor = text('myhomelib-domain/src/main/java/com/myhomelibcorp/domain/model/annotation/AnnotationAnchor.java')
annotation = text('myhomelib-domain/src/main/java/com/myhomelibcorp/domain/model/annotation/Annotation.java')
relocator = text('myhomelib-domain/src/main/java/com/myhomelibcorp/domain/model/annotation/AnnotationAnchorRelocator.java')
port = text('myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/repository/AnnotationRepository.java')
service = text('myhomelib-application/src/main/java/com/myhomelibcorp/application/annotation/AnnotationService.java')
repo = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteAnnotationRepository.java')
tx_helper = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/CollectionTransactionExecutor.java')
migration = text('myhomelib-infrastructure/src/main/resources/db/migration/V57__annotations.sql')
transfer_port = text('myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/backup/UserDataTransferPort.java')
transfer = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/backup/VersionedUserDataTransferAdapter.java')

for token in ['bookId','artifactId','chapterId','startOffset','endOffset','position','quote','prefix','suffix']:
    need(token in anchor, f'annotation anchor missing {token}')
need('implements Serializable' in anchor and 'implements Serializable' in annotation, 'annotation model must be serializable')
need('allowsExactOffsetsFor' in anchor and 'Objects.equals' in anchor and 'rebind(' in anchor, 'artifact-switch exact-offset/rebind policy missing')
need('bestQuoteMatch' in relocator and 'contextScore' in relocator, 'quote/context relocation strategy missing')
need('AnnotationRepository' in port and 'findByBookId' in port and 'save(' in port, 'application annotation repository port incomplete')
need('AnnotationRepository' in service and 'Sqlite' not in service, 'annotation application service must depend on port, not SQLite')
need('updateTags' in service and 'reanchor' in service, 'annotation lifecycle update/reanchor operations incomplete')
for table in ['annotations','annotation_anchors','annotation_tags']:
    need(f'CREATE TABLE IF NOT EXISTS {table}' in migration, f'V57 missing {table}')
need('ON DELETE CASCADE' in migration, 'annotation child cleanup policy missing')
need('CollectionTransactionExecutor' in repo and 'CollectionTransactionExecutor.run(collectionManager' in repo and
     'TransactionTemplate' in tx_helper and 'DataSourceTransactionManager' in tx_helper,
     'annotation save/delete must be transaction-bound')
need('DELETE FROM annotation_tags' in repo and 'DELETE FROM annotation_anchors' in repo, 'annotation delete must not rely only on PRAGMA foreign_keys cascade')
need('CURRENT_SCHEMA_VERSION = 4' in transfer_port, 'portable user-data schema v4 missing')
for section in ['"annotations"','"annotationTags"','exportAnnotations','restoreAnnotationRow']:
    need(section in transfer, f'annotation portable transfer missing {section}')
need('book_artifacts WHERE book_id=? AND artifact_id=?' in transfer,
     'portable restore must validate artifact binding against target book')
need('sourceVersion >= 4' in transfer, 'v1-v3 backward compatibility gate for annotations missing')
need('long annotations' in transfer_port and 'counters.annotations' in transfer, 'portable annotation result counters missing')
need('highlight requires selected quote text' in annotation and 'Restored highlight requires selected quote text' in transfer, 'highlight quote invariant missing')
need('DELETE FROM annotation_tags WHERE annotation_id=?' in transfer, 'portable restore must replace, not append, annotation tags')
need('V57__annotations.sql' in '\n'.join(str(p.relative_to(ROOT)) for p in (ROOT/'myhomelib-infrastructure/src/main/resources/db/migration').glob('V57__*.sql')),
     'V57 annotation migration not discoverable')

if errors:
    print('STAGE 35 ANNOTATIONS CHECK: FAILED')
    for error in errors: print(' -', error)
    sys.exit(1)
print('STAGE 35 ANNOTATIONS CHECK: PASS')
print(' - renderer-independent serializable annotation/anchor model: PRESENT')
print(' - quote/context relocation + explicit artifact switch policy: PRESENT')
print(' - application port/service + transactional SQLite V57 persistence: PRESENT')
print(' - portable user-data schema v4 annotations + legacy compatibility: PRESENT')
