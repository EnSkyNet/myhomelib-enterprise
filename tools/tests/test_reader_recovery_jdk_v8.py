import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class ReaderRecoveryJdkSmokeTest(unittest.TestCase):
    def test_cross_process_conditional_acknowledgements(self):
        completed = subprocess.run([sys.executable, str(ROOT / "tools/reader-recovery-jdk-smoke.py")],
                                   cwd=ROOT, capture_output=True, text=True, timeout=100)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("PASS recovery guard checks=", completed.stdout)
