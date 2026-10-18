package com.myhomelibcorp.organizer.journal;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrganizationJournalItemTest {
    private OrganizationJournalItem item(String hash) {
        return new OrganizationJournalItem(UUID.randomUUID(), UUID.randomUUID(),
                Path.of("source.fb2"), "inside.fb2", Path.of("target.epub"),
                hash, "target-hash", "CONVERT", OrganizationItemState.VALIDATED,
                Path.of("backup.fb2"), "BALANCED", 100, 90, 10, 10.0,
                "{\"optimized\":true}", "EPUB33", "DEVICE", "ZIP", "PASSED",
                "OLD_ERROR", "old error");
    }

    @Test
    void rollbackClearsErrorsWithoutDroppingOptimizationMetadata() {
        var original = item("original-sha");
        var updated = original.withOutcome(OrganizationItemState.ROLLED_BACK, "", "", "");
        assertThat(updated).isEqualTo(new OrganizationJournalItem(
                original.operationId(), original.itemId(), original.sourcePath(),
                original.sourceEntry(), original.targetPath(), original.sourceSha256(),
                original.targetSha256(), original.action(), OrganizationItemState.ROLLED_BACK,
                original.backupPath(), original.optimizationProfile(), original.sizeBefore(),
                original.sizeAfter(), original.bytesSaved(), original.percentSaved(),
                original.optimizationSummaryJson(), original.targetStandard(),
                original.deviceProfile(), original.compressionBackend(),
                original.validationStatus(), "", ""));
    }

    @Test
    void failureUsesFallbackOnlyWhenSourceChecksumWasUnknown() {
        var unknown = item("");
        var failed = unknown.withOutcome(OrganizationItemState.FAILED, "verified-sha",
                "APPLY_FAILED", "copy interrupted");
        assertThat(failed.sourceSha256()).isEqualTo("verified-sha");
        assertThat(failed.targetSha256()).isEqualTo("target-hash");
        assertThat(failed.state()).isEqualTo(OrganizationItemState.FAILED);
        assertThat(failed.errorMessage()).isEqualTo("copy interrupted");
        assertThat(item("original-sha").withOutcome(OrganizationItemState.FAILED,
                "later-sha", "ERR", "bad").sourceSha256()).isEqualTo("original-sha");
    }
}
