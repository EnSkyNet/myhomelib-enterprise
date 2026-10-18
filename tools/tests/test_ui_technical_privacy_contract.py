"""Static contract preventing technical identifiers from leaking into ordinary JavaFX UI text."""
from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "myhomelib-ui/src/main/java/com/myhomelibcorp/ui"

FORBIDDEN_USER_OUTPUT = (
    r"\.setText\([^;\n]*(?:getId\(\)|mergeId\(\)|scanId\(\)|fingerprintSha256\(\)|packageSha256\(\))",
    r"(?:setText|append|showInfo|showError|showWarning)\([^;\n]*\.sha256\(\)",
)

class UiTechnicalPrivacyContract(unittest.TestCase):
    def test_no_known_technical_identifiers_are_written_directly_to_ui(self):
        for source in UI.rglob("*.java"):
            if "/test/" in source.as_posix():
                continue
            text = source.read_text("utf-8")
            for pattern in FORBIDDEN_USER_OUTPUT:
                with self.subTest(source=source.relative_to(ROOT), pattern=pattern):
                    self.assertIsNone(re.search(pattern, text, re.IGNORECASE))

    def test_sensitive_identifier_helpers_are_not_rendered_by_integration_center(self):
        source = (UI / "integration/IntegrationCenterDialog.java").read_text("utf-8")
        for token in (
            "ui.integration.details.id",
            "ui.integration.details.sha256",
            "selected.packageSha256()",
            "selected.pluginId() +",
            "p.pluginId() +",
            "p.packageSha256()",
            "status.deviceId()));",
            "current.deviceId());",
            "config.deviceId());",
        ):
            self.assertNotIn(token, source)

    def test_bulk_and_duplicate_previews_do_not_render_book_ids(self):
        bulk = (UI / "metadata/BulkMetadataEditUiService.java").read_text("utf-8")
        duplicate = (UI / "duplicate/DuplicateReviewUiService.java").read_text("utf-8")
        self.assertNotIn("text.append(item.bookId())", bulk)
        self.assertNotIn("artifact.bookId().asString()", duplicate)

    def test_content_index_health_uses_collection_name_not_internal_id(self):
        source = (UI / "controller/IntegrityCheckController.java").read_text("utf-8")
        self.assertIn("currentCollectionName()", source)
        self.assertNotIn("health.collectionId() +", source)

if __name__ == "__main__":
    unittest.main()
