#!/usr/bin/env python3
"""Offline tests for the pinned HttpClient staging verifier."""
import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).with_name("stage-epubcheck-httpclient-fix.py")
spec = importlib.util.spec_from_file_location("stage_epubcheck_httpclient", SCRIPT)
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)


def fake_client_jar(version="5.6.5"):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr(
            "META-INF/maven/org.apache.httpcomponents.client5/httpclient5/pom.properties",
            "groupId=org.apache.httpcomponents.client5\n"
            "artifactId=httpclient5\n"
            f"version={version}\n",
        )
        archive.writestr(
            "META-INF/maven/org.apache.httpcomponents.client5/httpclient5/pom.xml",
            """<project xmlns="http://maven.apache.org/POM/4.0.0">
              <parent>
                <groupId>org.apache.httpcomponents.client5</groupId>
                <artifactId>httpclient5-parent</artifactId>
                <version>5.6.5</version>
              </parent>
              <artifactId>httpclient5</artifactId>
            </project>""",
        )
    return buffer.getvalue()


def fake_parent(core="5.4.4"):
    return f"""<project xmlns="http://maven.apache.org/POM/4.0.0">
      <groupId>org.apache.httpcomponents.client5</groupId>
      <artifactId>httpclient5-parent</artifactId>
      <version>5.6.5</version>
      <properties><httpcore.version>{core}</httpcore.version></properties>
    </project>""".encode()


class HttpClientStageTest(unittest.TestCase):
    def test_sha512_parser_accepts_maven_checksum_formats(self):
        digest = "a" * 128
        self.assertEqual(digest, stage.parse_sha512(digest.encode()))
        self.assertEqual(digest, stage.parse_sha512((digest + "  artifact.jar\n").encode()))

    def test_sha512_parser_rejects_malformed_checksum(self):
        with self.assertRaises(ValueError):
            stage.parse_sha512(b"not-a-checksum")

    def test_embedded_jar_identity_and_parent_version_are_checked(self):
        stage.verify_jar_identity(fake_client_jar())
        stage.verify_parent_pom(fake_parent())

    def test_wrong_embedded_version_is_rejected(self):
        with self.assertRaises(ValueError):
            stage.verify_jar_identity(fake_client_jar("5.1.3"))

    def test_wrong_httpcore_parent_version_is_rejected(self):
        with self.assertRaises(ValueError):
            stage.verify_parent_pom(fake_parent("5.3.4"))

    def test_staging_refuses_nonempty_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            (path / "keep.txt").write_text("do not overwrite", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "new or empty"):
                stage.stage(path)
            self.assertEqual("do not overwrite", (path / "keep.txt").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
