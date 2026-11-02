#!/usr/bin/env python3
"""Offline regression tests for EPUBCheck runtime-manifest updates."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("apply-epubcheck-httpclient-fix.py")
SPEC = importlib.util.spec_from_file_location("apply_epubcheck_httpclient", SCRIPT)
apply = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(apply)


class EpubcheckManifestUpdateTest(unittest.TestCase):
    def test_long_class_path_preserves_spaces_across_manifest_folds(self):
        source = [
            "Manifest-Version: 1.0",
            "Class-Path: " + " ".join([
                "lib/httpclient5-5.6.5.jar",
                "lib/httpcore5-5.4.4.jar",
                "lib/httpcore5-h2-5.4.4.jar",
                "lib/listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar",
            ]),
            "Main-Class: com.adobe.epubcheck.tool.Checker",
        ]
        encoded = apply.wrap_manifest(source)
        self.assertEqual(source, apply.unfold_manifest(encoded))
        self.assertTrue(all(
            len(line) <= 72 for line in encoded.split(b"\r\n") if line
        ))

    def test_manifest_references_replaced_jars_as_independent_tokens(self):
        with tempfile.TemporaryDirectory() as temp:
            vendor = Path(temp) / "epubcheck"
            lib = vendor / "lib"
            lib.mkdir(parents=True)
            original = [
                "lib/httpclient5-5.1.3.jar",
                "lib/httpcore5-5.1.3.jar",
                "lib/httpcore5-h2-5.1.3.jar",
                "lib/slf4j-api-1.7.36.jar",
            ]
            # The future client JAR is intentionally absent until the
            # checked vendor replacement is actually published.
            for filename in (
                "httpcore5-5.4.4.jar", "httpcore5-h2-5.4.4.jar",
                "slf4j-api-1.7.36.jar",
            ):
                (lib / filename).write_bytes(b"test")
            source = ("Manifest-Version: 1.0\r\n"
                      "Class-Path: " + " ".join(original) + "\r\n\r\n").encode()
            result = apply.update_manifest(
                source, lib, future_files={"lib/httpclient5-5.6.5.jar"}
            )
            rebuilt = apply.unfold_manifest(result)
            classpaths = [line for line in rebuilt if line.startswith("Class-Path: ")]
            self.assertEqual(1, len(classpaths))
            self.assertEqual([
                "lib/httpclient5-5.6.5.jar",
                "lib/httpcore5-5.4.4.jar",
                "lib/httpcore5-h2-5.4.4.jar",
                "lib/slf4j-api-1.7.36.jar",
            ], classpaths[0].split(": ", 1)[1].split())
            self.assertNotIn("5.1.3.jar", classpaths[0])

    def test_long_non_class_path_fields_and_unicode_round_trip(self):
        source = [
            "Manifest-Version: 1.0",
            "Created-By: " + ("Long Field Value with spaces " * 8),
            "Implementation-Title: " + ("Українська назва " * 12),
        ]
        encoded = apply.wrap_manifest(source)
        self.assertTrue(encoded.endswith(b"\r\n\r\n"))
        self.assertEqual(source, apply.unfold_manifest(encoded))
        self.assertTrue(all(
            len(line) <= 72 for line in encoded.split(b"\r\n") if line
        ))

    def test_unfold_rejects_orphan_continuation(self):
        with self.assertRaisesRegex(ValueError, "continuation"):
            apply.unfold_manifest(b" orphan\r\n")


if __name__ == "__main__":
    unittest.main()
