#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
port = (ROOT / 'myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/cover/ArchiveReader.java').read_text(encoding='utf-8')
resource_port = (ROOT / 'myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/resource/BookResourcePort.java').read_text(encoding='utf-8')
resolver = (ROOT / 'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/resource/BookResourceResolver.java').read_text(encoding='utf-8')
adapter = (ROOT / 'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/cover/ZipArchiveReader.java').read_text(encoding='utf-8')
ui = (ROOT / 'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java').read_text(encoding='utf-8')
reader_source = (ROOT / 'myhomelib-application/src/main/java/com/myhomelibcorp/application/reader/ReaderSourceResolver.java').read_text(encoding='utf-8')

checks = {
    'ArchiveReader exposes bounded cancellable materialization':
        'boolean materializeEntry(Path archivePath, String entryName, Path target, long maxBytes,' in port
        and 'BooleanSupplier cancelled' in port,
    'BookResourcePort separates container locate from member materialization':
        'Optional<Path> locateBookContainer(Book book);' in resource_port
        and 'Optional<String> materializeArchiveBookEntry(' in resource_port,
    'Reader resolves physical container without member enumeration':
        'ReaderSourceResolver.resolve(book, requestedArtifactId, bookResourcePort)' in ui
        and 'bookResourcePort.locateBookContainer(candidate)' in reader_source,
    'Reader delegates archive copy to bounded application port':
        'ReaderSourceResolver.materialize(book, physicalPath, bookResourcePort' in ui
        and 'bookResourcePort.materializeArchiveBookEntry(' in reader_source
        and 'ArchiveSafetyLimits.MAX_ENTRY_BYTES' in reader_source
        and 'bookResourcePort.readBookData(book)' not in reader_source,
    'Resolver resolves compatibility once then materializes actual member':
        'actualEntry = resolveArchiveEntry(safeArchive, requestedEntry, book.getFileName()).orElse(null);' in resolver
        and 'archiveReader.materializeEntry(safeArchive, actualEntry, target, maxBytes, cancelled)' in resolver,
    'ZIP materialization uses guarded staging publish and compression-ratio guard':
        'materializeZipEntry(' in adapter and 'validateZipEntry(entry, limit);' in adapter
        and 'SafeFilePublication.publishReplacingIfUnchanged(' in adapter
        and 'snapshotExistingTarget(' in adapter,
    '7z/RAR/stream archives have direct materialization paths': all(token in adapter for token in (
        'materialize7zEntry(', 'materializeRarEntry(', 'materializeStreamArchiveEntry(')),
    'materialization has bounded read loop and cancellation':
        'copyBounded(InputStream in, OutputStream out, long limit, BooleanSupplier cancelled' in adapter
        and 'checkCancelled(cancelled);' in adapter
        and 'accountBytes(total, read, limit' in adapter,
    'materialization does not use full-entry readAllBytes/transferTo':
        'readAllBytes(' not in adapter and 'transferTo(' not in adapter
        and 'readAllBytes(' not in ui and 'transferTo(' not in ui
        and 'readAllBytes(' not in reader_source and 'transferTo(' not in reader_source,
    'Reader lifecycle deletes materialized temp':
        'if (!success) Files.deleteIfExists(temp)' in reader_source
        and 'prepared.closeAbandoned();' in ui
        and 'cleanupMaterializedBookFile();' in ui
        and 'task.cancel(true)' in ui,
    'Reader timing records only phases/format/size':
        'reader_open_timing resolve_ms={} materialize_ms={} parse_ms={} render_ready_ms={} total_ms={} format={} size_bytes={}' in ui,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ': ' + name)
if failed:
    sys.exit(1)
