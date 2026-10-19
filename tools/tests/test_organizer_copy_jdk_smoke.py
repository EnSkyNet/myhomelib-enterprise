"""Ensure the real organizer publication regression harness remains executable."""
from __future__ import annotations
import pathlib
import shutil
import subprocess
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which('java') and shutil.which('javac'), 'Java 21 JDK unavailable')
class OrganizerCopyJdkSmokeTest(unittest.TestCase):
    def test_live_java_copy_cancel_collision_and_unsupported_hardlink(self):
        process = subprocess.run(
            [sys.executable, str(ROOT / 'tools/organizer-copy-jdk-smoke.py')],
            cwd=ROOT, capture_output=True, text=True, timeout=90, check=False)
        self.assertEqual(process.returncode, 0, process.stdout + '\n' + process.stderr)
        self.assertIn('MANAGED_FILE_COPY_SMOKE: PASS 12 assertions', process.stdout)
        self.assertIn('ORGANIZER_PUBLISH_SMOKE: PASS 7 assertions', process.stdout)


if __name__ == '__main__':
    unittest.main()
