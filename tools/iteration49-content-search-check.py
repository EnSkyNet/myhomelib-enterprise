#!/usr/bin/env python3
"""Static closure gate for Iteration 49 / MHL-301 + MHL-302 + MHL-306."""
from pathlib import Path
import csv
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = []

def text(rel):
    return (ROOT / rel).read_text(encoding="utf-8")

def contains(rel, *needles):
    value = text(rel)
    return all(needle in value for needle in needles)

def check(label, ok):
    checks.append((label, bool(ok)))
    print(("PASS" if ok else "FAIL") + ": " + label)

check("ContentExtractor SPI is application-owned and format-neutral",
      contains("myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/content/ContentExtractor.java",
               "interface ContentExtractor", "supports(", "extract(") and
      contains("myhomelib-application/src/main/java/com/myhomelibcorp/application/content/ContentExtractionResult.java",
               "ContentExtractionStatus"))

check("FB2/EPUB/TXT extractors live in infrastructure",
      all((ROOT / f"myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/content/{name}ContentExtractor.java").is_file()
          for name in ("Fb2", "Epub", "Txt")))

infra_content = "\n".join(path.read_text(encoding="utf-8")
                            for path in (ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/content").glob("*.java"))
check("content extraction does not introduce infrastructure-to-reader dependency",
      "com.myhomelibcorp.reader" not in infra_content)

check("independent content-index port exposes replace/delete/rebuild/search/health",
      contains("myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/content/ContentIndexPort.java",
               "replaceArtifact", "deleteArtifact", "deleteBook", "rebuild", "search", "health"))

check("content index has physical path separation and schema versioning",
      contains("myhomelib-shared/src/main/java/com/myhomelibcorp/shared/util/AppPaths.java",
               'resolve("content-index")', "collectionContentIndexDir") and
      contains("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/contentindex/LuceneContentIndexService.java",
               "SCHEMA_VERSION", "SCHEMA_KEY", "IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS"))

check("content rebuild uses candidate directory and atomic/fallback swap",
      contains("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/contentindex/LuceneContentIndexService.java",
               ".rebuild-", ".backup-", "swapDirectories", "ATOMIC_MOVE"))

migration = "myhomelib-infrastructure/src/main/resources/db/migration/V58__annotation_full_text_search.sql"
check("annotation FTS5 index is local and trigger-synchronized",
      contains(migration, "CREATE VIRTUAL TABLE", "fts5", "annotation_search_fts",
               "trg_annotation_search_annotations_ai", "trg_annotation_search_annotations_au", "trg_annotation_search_anchors_au", "trg_annotation_search_tags_ai", "trg_annotation_search_books_au"))

check("annotation query uses FTS plus existing local filters",
      contains("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteAnnotationManagerQueryAdapter.java",
               "annotation_search_fts MATCH", "toFtsQuery", "AnnotationManagerFilter"))

check("global Search Workspace exposes annotations and opens exact Reader anchor",
      contains("myhomelib-ui/src/main/java/com/myhomelibcorp/ui/search/SearchWorkspaceController.java",
               "AnnotationManagerService", "annotationsListView", "showAnnotationInReader") and
      contains("myhomelib-ui/src/main/resources/view/search-workspace.fxml",
               'fx:id="annotationsSection"', 'fx:id="annotationsListView"'))

required_keys = {"ui.search.annotations.title", "ui.search.annotations.accessible", "ui.search.annotations.found"}
locales_ok = True
for root in (ROOT / "Lang", ROOT / "myhomelib-ui/src/main/resources/lang/default"):
    for lang in ("uk", "en", "bg"):
        data = json.loads((root / f"{lang}.json").read_text(encoding="utf-8"))
        locales_ok &= required_keys.issubset(data.get("translations", {}).keys())
check("annotation search strings exist in all bundled catalogs", locales_ok)

benchmark = ROOT / "docs/history/records/ITERATION-49-CONTENT-INDEX-BENCHMARK.csv"
benchmark_ok = False
if benchmark.is_file():
    with benchmark.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    benchmark_ok = bool(rows) and rows[-1].get("catalogue_sentinel_unchanged", "").lower() == "true" \
                   and int(rows[-1].get("documents", "0")) >= 5000
check("content-index benchmark covers >=5000 docs and preserves catalogue sentinel", benchmark_ok)

if not all(ok for _, ok in checks):
    sys.exit(1)
print(f"Iteration 49 content/search check: PASS ({len(checks)}/{len(checks)})")
