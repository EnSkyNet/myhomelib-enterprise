"""Lightweight source/catalog contract; not a substitute for JavaFX runtime tests."""
from __future__ import annotations
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KEYS = (
    'ui.reader.persistence.bookmark_count_error',
    'ui.reader.persistence.bookmark_delete_error',
    'ui.reader.persistence.bookmark_save_error',
    'ui.reader.persistence.bookmarks_load_error',
    'ui.reader.persistence.position_load_error',
)

class ReaderErrorPrivacyContract(unittest.TestCase):
    def test_localized_messages_do_not_require_internal_identifiers(self):
        for code in ('uk', 'en', 'bg'):
            with self.subTest(language=code):
                outer = json.loads((ROOT / 'Lang' / f'{code}.json').read_text('utf-8'))['translations']
                inner = json.loads((ROOT / 'myhomelib-ui/src/main/resources/lang/default' /
                                    f'{code}.json').read_text('utf-8'))['translations']
                for key in KEYS:
                    self.assertEqual(outer[key], inner[key])
                    self.assertTrue(outer[key].strip())
                    self.assertNotIn('%s', outer[key])
                    self.assertNotIn('{0}', outer[key])

    def test_reader_uses_localized_error_without_uuid_argument(self):
        src = (ROOT / 'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/'
               'NewReaderPersistenceService.java').read_text('utf-8')
        for key in KEYS:
            self.assertIn(f'i18n.text("{key}")', src)
            self.assertNotIn(f'i18n.format("{key}"', src)

if __name__ == '__main__':
    unittest.main()
