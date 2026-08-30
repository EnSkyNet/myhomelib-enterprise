#!/usr/bin/env python3
from pathlib import Path
import subprocess, tempfile, textwrap

ROOT = Path(__file__).resolve().parents[1]

def text(rel):
    return (ROOT / rel).read_text(encoding='utf-8')

helper_rel = 'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/helper/AuthorSearchNameNormalizer.java'
helper = text(helper_rel)
repo = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/persistence/sqlite/SqliteAuthorRepository.java')
migration = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/adapter/DatabaseMigrationAdapter.java')
writer = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/JdbcBatchWriter.java')
catalog_writer = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/catalog/importing/JdbcCatalogBatchWriter.java')
legacy = text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/collection/legacy/SqliteLegacyCollectionAttachAdapter.java')

assert '.toLowerCase(Locale.ROOT)' in helper, 'normalizer must use deterministic Unicode Java lower-case'
assert 'v71_author_search_name_unicode_normalized' in repo, 'one-time normalization marker missing'
assert 'WHERE id>? ORDER BY id LIMIT ?' in repo, 'legacy backfill must be keyset/bounded'
assert 'SEARCH_NAME_BACKFILL_BATCH = 1000' in repo, 'bounded backfill batch missing'
assert 'AuthorSearchNameNormalizer.normalize' in repo, 'repository writes must use canonical normalizer'
assert "COALESCE(search_name, '') LIKE ?" in repo, 'search must query canonical normalized key directly'
assert 'authorRepository.normalizeSearchNamesIfNeeded();' in migration, 'normalization must run after migration'
for name, src in [('JdbcBatchWriter', writer), ('JdbcCatalogBatchWriter', catalog_writer), ('SqliteLegacyCollectionAttachAdapter', legacy)]:
    assert 'AuthorSearchNameNormalizer.normalize' in src, f'{name} must use canonical author normalizer'

# Pure-JDK runtime smoke for the helper, including Cyrillic and trimming/order.
with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    pkg = td / 'com/myhomelibcorp/infrastructure/persistence/sqlite/helper'
    pkg.mkdir(parents=True)
    (pkg / 'AuthorSearchNameNormalizer.java').write_text(helper, encoding='utf-8')
    (td / 'Smoke.java').write_text(textwrap.dedent('''
        import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.AuthorSearchNameNormalizer;
        public class Smoke {
          public static void main(String[] args) {
            eq("шевченко тарас григорович", AuthorSearchNameNormalizer.normalize(" Тарас ", "ГРИГОРОВИЧ", "ШЕВЧЕНКО"));
            eq("іваненко іван", AuthorSearchNameNormalizer.normalize("ІВАН", null, "ІВАНЕНКО"));
            eq("", AuthorSearchNameNormalizer.normalize(" ", null, ""));
          }
          static void eq(String e, String a) { if (!e.equals(a)) throw new AssertionError(e + " != " + a); }
        }
    '''), encoding='utf-8')
    subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(td), str(pkg / 'AuthorSearchNameNormalizer.java'), str(td / 'Smoke.java')], check=True)
    subprocess.run(['java', '-cp', str(td), 'Smoke'], check=True)

print('AUTHOR SEARCH NORMALIZATION CHECK: PASS')
print(' - one canonical Java Unicode normalizer used by all audited write paths')
print(' - legacy backfill is keyset/bounded and marker-gated')
print(' - Cyrillic runtime normalization smoke: PASS')
