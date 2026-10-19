"""Check that every Reader read/save path enters the shared OS transaction guard.
The JDK smoke separately exercises actual Java file locks.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
FILE = ROOT / 'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader/NewReaderPersistenceService.java'


class ReaderTransactionIntegrationContractV9(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.content = FILE.read_text(encoding='utf-8')

    def test_all_four_position_transitions_are_serialized(self):
        for method, next_method in (
            ('savePosition(String collectionId', 'loadPosition(String bookId'),
            ('loadPosition(String collectionId', 'private static boolean isValidTextAnchor'),
            ('saveAudioPosition(String collectionId', 'loadAudioPosition(String bookId'),
            ('loadAudioPosition(String collectionId', 'private void acknowledgeAudioRecoveryBestEffort'),
        ):
            with self.subTest(method=method):
                start = self.content.index(method)
                end = self.content.index(next_method, start)
                body = self.content[start:end]
                self.assertIn('progressTransactionGuard.withLock(collectionId, bookId, () -> withReaderState(', body)
                self.assertNotIn('return withReaderState(collectionId, () -> {', body)

    def test_guard_is_process_shared_and_fail_closed(self):
        guard = (FILE.parent / 'ReaderProgressTransactionGuard.java').read_text(encoding='utf-8')
        for needle in ('FileChannel.open(', 'tryLock()', 'LinkOption.NOFOLLOW_LINKS',
                       'AtomicFileSupport.requireSafeRegularFile',
                       'reader-progress-transactions', 'TIMEOUT',
                       'throw new IOException(', 'JVM_LOCKS[stripe]'):
            # Timeout is instance-specific (timeoutNanos) rather than a static constant.
            if needle == 'TIMEOUT': needle = 'timeoutNanos'
            self.assertIn(needle, guard)


if __name__ == '__main__':
    unittest.main()
