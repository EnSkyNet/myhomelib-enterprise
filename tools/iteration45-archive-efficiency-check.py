#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
resolver = (ROOT / 'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/resource/BookResourceResolver.java').read_text(encoding='utf-8')
reader = (ROOT / 'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/cover/ZipArchiveReader.java').read_text(encoding='utf-8')

checks = {
    'resolver uses ArchiveReader port': 'private final ArchiveReader archiveReader;' in resolver,
    'resolver no concrete ZipArchiveReader dependency': 'ZipArchiveReader' not in resolver,
    'resolver has single-list archive resolution': 'Optional<String> exact = entries.stream()' in resolver and 'resolveArchiveEntry(Path archivePath' in resolver,
    'legacy containsEntry shortcut removed': 'containsEntry(' not in resolver and 'containsEntry(' not in reader,
    'findFirstEntry does not list then reopen': 'for (String name : listEntries(archivePath))' not in reader,
    'findFirstEntry dispatches direct traversal': all(x in reader for x in (
        'findFirstZipEntry(archivePath, filter)',
        'findFirst7zEntry(archivePath, filter)',
        'findFirstRarEntry(archivePath, filter)',
        'findFirstStreamArchiveEntry(archivePath, filter)',
    )),
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ': ' + name)
if failed:
    sys.exit(1)
