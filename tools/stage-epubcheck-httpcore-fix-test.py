#!/usr/bin/env python3
"""Offline regression tests for safe staging of EPUBCheck security replacements."""
import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPT = Path(__file__).with_name("stage-epubcheck-httpcore-fix.py")
spec = importlib.util.spec_from_file_location("stage_epubcheck_httpcore_fix", SCRIPT)
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)

class FakeResponse(io.BytesIO):
    def __enter__(self):
        return self
    def __exit__(self, *_):
        self.close()

def jar_bytes(name="httpcore5", version="5.4.4"):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as bundle:
        bundle.writestr(
            f"META-INF/maven/org.apache.httpcomponents.core5/{name}/pom.properties",
            f"version={version}\nartifactId={name}\ngroupId=org.apache.httpcomponents.core5\n"
        )
    return buffer.getvalue()

class StageSafetyTest(unittest.TestCase):
    def test_valid_signed_by_digest_metadata_is_staged_without_mutating_other_files(self):
        data = jar_bytes()
        digest = hashlib.sha256(data).hexdigest().encode()
        with tempfile.TemporaryDirectory() as d:
            target = Path(d)
            def open_mock(url, timeout):
                return FakeResponse(digest if url.endswith(".sha256") else data)
            with patch.object(stage.urllib.request, "urlopen", side_effect=open_mock):
                path, actual = stage.verified_artifact(
                    "org.apache.httpcomponents.core5", "httpcore5", "5.4.4", target)
            self.assertEqual(digest.decode(), actual)
            self.assertEqual(data, path.read_bytes())
            with patch.object(stage.urllib.request, "urlopen", side_effect=open_mock):
                with self.assertRaises(FileExistsError):
                    stage.verified_artifact("org.apache.httpcomponents.core5", "httpcore5", "5.4.4", target)

    def test_wrong_digest_never_writes_vendor_bytes(self):
        data = jar_bytes()
        with tempfile.TemporaryDirectory() as d:
            target = Path(d)
            def open_mock(url, timeout):
                return FakeResponse(b"0" * 64 if url.endswith(".sha256") else data)
            with patch.object(stage.urllib.request, "urlopen", side_effect=open_mock):
                with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                    stage.verified_artifact("org.apache.httpcomponents.core5", "httpcore5", "5.4.4", target)
            self.assertEqual([], list(target.iterdir()))

    def test_mismatched_embedded_version_never_writes_bytes(self):
        data = jar_bytes(version="5.1.3")
        digest = hashlib.sha256(data).hexdigest().encode()
        with tempfile.TemporaryDirectory() as d:
            target = Path(d)
            def open_mock(url, timeout):
                return FakeResponse(digest if url.endswith(".sha256") else data)
            with patch.object(stage.urllib.request, "urlopen", side_effect=open_mock):
                with self.assertRaises(AssertionError):
                    stage.verified_artifact("org.apache.httpcomponents.core5", "httpcore5", "5.4.4", target)
            self.assertEqual([], list(target.iterdir()))

if __name__ == "__main__":
    unittest.main()
