#!/usr/bin/env python3
"""Regression guard against turning persistence/index failures into empty/zero data."""
from pathlib import Path
import sys
ROOT = Path(__file__).resolve().parents[1]
errors=[]

def text(rel):
    return (ROOT/rel).read_text(encoding="utf-8")

collection = text("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteCollectionRepository.java")
findall = collection[collection.index("public List<Collection> findAll()") : collection.index("public Optional<Collection> findById")]
if "catch (" in findall or "return List.of()" in findall:
    errors.append("SqliteCollectionRepository.findAll still hides metadata DB failure")

lucene = text("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/search/LuceneSearchService.java")
count = lucene[lucene.index("public int getDocumentCount()") : lucene.index("public Optional<SearchIndexPerformanceReport>")]
if "catch (" in count:
    errors.append("LuceneSearchService.getDocumentCount still hides index failure")

maintenance = text("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/maintenance/CollectionMaintenanceAnalyzer.java")
block = maintenance[maintenance.index("private Optional<Path> configuredSource") : maintenance.index("private static String pathKey")]
if "return Optional.empty();" in block and "catch" in block:
    errors.append("maintenance configuredSource still converts DB error to Optional.empty")
if "throw new IllegalStateException" not in block:
    errors.append("maintenance configuredSource does not surface metadata failure")

legacy = text("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/collection/legacy/SqliteLegacyCollectionAttachAdapter.java")
for marker in ("Cannot migrate Series", "Cannot migrate genres", "Cannot migrate author links", "Cannot migrate genre links"):
    if marker in legacy:
        errors.append(f"legacy migration still logs-and-continues: {marker}")

legacy_value = legacy[legacy.index("private static String value(") : legacy.index("private static long scalar(")]
if "catch (Exception" in legacy_value or "catch(Exception" in legacy_value:
    errors.append("legacy value/numeric helpers still swallow arbitrary ResultSet failures")
if "catch (SQLException labelFailure)" not in legacy_value or "throw new IllegalStateException" not in legacy_value:
    errors.append("legacy column read failures are not surfaced")
if legacy_value.count("catch (NumberFormatException") < 3:
    errors.append("legacy numeric fallbacks are not limited to malformed numbers")

reader_prefs = text("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/reader/ReaderBookPreferencesService.java")
migration_check = reader_prefs[reader_prefs.index("private boolean migrationCompleted") : reader_prefs.index("private long migrateLegacyFile")]
if "catch (Exception" in migration_check or "catch(Exception" in migration_check:
    errors.append("Reader settings migration check still treats arbitrary persistence failure as not-migrated")
if "catch (DataAccessException persistenceFailure)" not in migration_check or "throw new IllegalStateException" not in migration_check:
    errors.append("Reader settings migration check does not surface unexpected DB failures")
if "isMissingSettingsTable" not in migration_check:
    errors.append("Reader settings migration check lacks narrow pre-V42 missing-table fallback")

if errors:
    print("PERSISTENCE ERROR TRANSPARENCY: FAIL")
    for error in errors: print(" -", error)
    sys.exit(1)
print("PERSISTENCE ERROR TRANSPARENCY: PASS")
print(" - metadata DB failures are not represented as an empty collection list")
print(" - Lucene count failures are not represented as zero documents")
print(" - maintenance/legacy DB failures fail the operation instead of producing partial state")
print(" - Reader migration probes distinguish missing schema from real DB failure")
print(" - legacy numeric fallbacks no longer swallow ResultSet failures")
