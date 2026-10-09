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


reader_global_prefs = text("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/reader/ReaderPreferencesService.java")
save_block = reader_global_prefs[reader_global_prefs.index("public void savePreferences") : reader_global_prefs.index("private ReaderPreferences readExistingPreferences")]
reset_block = reader_global_prefs[reader_global_prefs.index("public void resetPreferences") :]
if 'throw new IllegalStateException("Не вдалося зберегти налаштування Reader"' not in save_block:
    errors.append("global Reader settings save still hides persistence failure from the UI")
if 'throw new IllegalStateException("Не вдалося скинути налаштування Reader"' not in reset_block:
    errors.append("global Reader settings reset still hides persistence failure from the UI")

session = text("myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteSessionRepository.java")
get_session = session[session.index("public String getLastOpenedBookId") : session.index("public void clearSession")]
clear_session = session[session.index("public void clearSession") :]
if get_session.index("prefs.get(prefKey") > get_session.index("queryExecutor.queryForObject"):
    errors.append("Reader session still prefers potentially stale SQLite state over the write-through Preferences fallback")
if "PREF_CLEARED_PREFIX" not in session or "prefs.putBoolean(clearedKey, true)" not in clear_session:
    errors.append("Reader session clear lacks a tombstone against stale SQLite resurrection")

kobo = text("myhomelib-application/src/main/java/com/myhomelibcorp/application/kobo/KoboSyncService.java")
book_lookup = kobo[kobo.index("public Optional<KoboBookProjection> book") : kobo.index("public Optional<KoboProgressState> progress")]
if "catch (RuntimeException invalidId)" in book_lookup:
    errors.append("Kobo book lookup still hides repository failures as an invalid/missing book")
if "catch (IllegalArgumentException invalidId)" not in book_lookup:
    errors.append("Kobo malformed book IDs lack a narrow validation fallback")

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
print(" - global Reader settings save/reset failures propagate to the UI")
print(" - Reader session Preferences/tombstone prevent stale SQLite resurrection")
print(" - Kobo lookup distinguishes malformed IDs from repository failures")
