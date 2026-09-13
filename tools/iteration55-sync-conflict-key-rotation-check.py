#!/usr/bin/env python3
from pathlib import Path
import sys
root=Path(__file__).resolve().parents[1]
checks={
 'conflict resolver': root/'myhomelib-application/src/main/java/com/myhomelibcorp/application/sync/conflict/ConflictResolver.java',
 'review DTO boundary': root/'myhomelib-application/src/main/java/com/myhomelibcorp/application/sync/conflict/SyncConflictReviewItem.java',
 'review dialog': root/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/sync/SyncConflictReviewDialog.java',
 'key ring': root/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/sync/webdav/SyncKeyRing.java',
 'versioned envelope': root/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/sync/webdav/AesGcmSyncPayloadEnvelope.java',
 'credential rotation': root/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/sync/webdav/WebDavCredentialStore.java',
 'webdav migration': root/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/sync/webdav/WebDavSyncAdapter.java',
}
errors=[]
for name,path in checks.items():
    if not path.is_file(): errors.append(f'missing {name}: {path.relative_to(root)}')
ui=(root/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/sync/SyncConflictReviewModel.java').read_text()
if 'domain.model.sync' in ui or 'SyncRecord' in ui: errors.append('UI conflict model leaks domain sync record')
env=(root/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/sync/webdav/AesGcmSyncPayloadEnvelope.java').read_text()
for token in ['LEGACY_VERSION = 1','VERSION = 2','needsRotation','rewrap','keyRing.require']:
    if token not in env: errors.append(f'envelope contract missing {token}')
store=(root/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/sync/webdav/WebDavCredentialStore.java').read_text()
for token in ['rotateSyncKey','completeSyncKeyRotation','sync-key-previous']:
    if token not in store: errors.append(f'credential rotation missing {token}')
webdav=(root/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/sync/webdav/WebDavSyncAdapter.java').read_text()
if 'migrateEncryptionToActiveKey' not in webdav or 'Overwrite", "T"' not in webdav:
    errors.append('WebDAV remote migration contract incomplete')
print('ITERATION 55 CHECK:', 'PASS' if not errors else 'FAIL')
for e in errors: print(' -',e)
sys.exit(1 if errors else 0)
