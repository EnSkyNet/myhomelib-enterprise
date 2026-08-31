#!/usr/bin/env python3
from pathlib import Path
root=Path(__file__).resolve().parents[1]
co=(root/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/BookDownloadCoordinator.java').read_text(encoding='utf-8')
ad=(root/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/HttpOnlineBookDownloadAdapter.java').read_text(encoding='utf-8')
no=(root/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/InpxBookNormalizer.java').read_text(encoding='utf-8')
ut=(root/'myhomelib-application/src/main/java/com/myhomelibcorp/application/catalog/LegacyOnlineBookLocation.java').read_text(encoding='utf-8')
assert 'LegacyOnlineBookLocation.archivePath' in co
assert 'LegacyOnlineBookLocation.archivePath' in no
assert 'effectiveBaseUrl(collection)' in ad
assert 'collectionWithEffectiveUrl' in ad
assert '.fb2.zip' in ut
assert 'URL/ConnectionScript online-колекції' in co
assert 'loadAuthoritative(book).thenCompose' in co
assert 'ensureLocalForOpenAuthoritative' in co
# The caller DTO must be updated before the completion callback can launch/open it.
copy_pos = co.find('if (error == null) copyStorageState(refreshed, book);')
fx_pos = co.find('Platform.runLater(() -> {', copy_pos)
assert copy_pos >= 0 and fx_pos > copy_pos
print('PASS online-book runtime compatibility guard')
