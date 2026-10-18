"""Cross-process lock verification of real Java sources, without Maven."""
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]

class ReaderTransactionJdkV9Test(unittest.TestCase):
    def test_cross_process_transaction_barrier(self):
        subprocess.run([sys.executable, str(ROOT / 'tools/reader-progress-transaction-jdk-smoke.py')],
                       check=True, timeout=50)

if __name__ == '__main__':
    unittest.main()
