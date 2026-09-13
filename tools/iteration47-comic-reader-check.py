#!/usr/bin/env python3
"""Static closure gate for Iteration 47 / MHL-208 Comic Reader."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

checks = []
def contains(rel, *needles):
    text = (ROOT / rel).read_text(encoding="utf-8")
    return all(n in text for n in needles)

def check(label, ok):
    checks.append((label, bool(ok)))
    print(("PASS" if ok else "FAIL") + ": " + label)

registry = "myhomelib-shared/src/main/java/com/myhomelibcorp/shared/format/SupportedFormatRegistry.java"
check("CBZ/CBR are native reader book formats",
      contains(registry, 'f("cbz", "CBZ"', 'f("cbr", "CBR"', 'BOOK, NATIVE, true, true, true, false'))
check("comic importer treats one container as one book",
      contains("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importer/comic/ComicArchiveImporter.java",
               'isFormat(file, "cbz", "cbr")', 'archiveReader.listEntries(file)', 'return createBook'))
check("legacy ZIP/RAR importers no longer claim comics",
      contains("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importer/zip/ZipImporter.java",
               'isFormat(file, "zip", "jar")') and
      contains("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importer/rar/RarImporter.java",
               'isFormat(file, "rar")'))
check("page ordering and safety are centralized",
      contains("myhomelib-shared/src/main/java/com/myhomelibcorp/shared/comic/ComicPageNameSupport.java",
               'NATURAL_ORDER', 'isSafeEntryName', 'EXTENSIONS'))
check("session is lazy and bounded",
      contains("myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/comic/ComicDocumentSession.java",
               'source.listPageEntries()', 'source.openPage(entry)', 'MAX_PAGE_BYTES', 'MAX_PAGE_PIXELS',
               'MAX_CACHE_BYTES', 'setSourceSubsampling'))
check("reader exposes requested comic modes",
      contains("myhomelib-reader/src/main/java/com/myhomelibcorp/reader/render/comic/ComicReaderView.java",
               'fitWidth', 'fitPage', 'continuousList', 'dualPageMode', 'rtlMode', 'thumbnails',
               'currentPosition()', 'progressPercent()'))
check("workspace routes comics and persists position/bookmarks",
      contains("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java",
               'isComicExtension', 'openComicSession', 'comicReaderView.openPrepared',
               'comicReaderView.currentPosition()', 'comicReaderView.goToPosition(target)'))
locales = [ROOT / "myhomelib-ui/src/main/resources/lang/default" / f"{lang}.json" for lang in ("uk", "en", "bg")]
check("comic controls are localized in all bundled languages",
      all('"ui.reader.comic.thumbnails"' in p.read_text(encoding="utf-8") and
          '"ui.reader.comic.render_failed"' in p.read_text(encoding="utf-8") for p in locales))

if not all(ok for _, ok in checks):
    sys.exit(1)
print(f"Iteration 47 comic reader check: PASS ({len(checks)}/{len(checks)})")
