package com.myhomelibcorp.organizer.fs;

import com.myhomelibcorp.organizer.duplicate.DuplicateDecision;
import com.myhomelibcorp.organizer.journal.OrganizationItemState;
import com.myhomelibcorp.organizer.journal.OrganizationOperationState;
import com.myhomelibcorp.organizer.plan.OrganizationAction;
import com.myhomelibcorp.organizer.plan.OrganizationPlan;
import com.myhomelibcorp.organizer.plan.OrganizationPlanItem;
import com.myhomelibcorp.organizer.plan.PlanStatistics;
import com.myhomelibcorp.organizer.policy.OrganizerProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibraryOperationCrashManifestEventLogTest {
    @TempDir Path temp;

    @Test
    void updatesOnlyEventLogAndReplaysLastStateWithoutRewritingBaseSnapshot() throws Exception {
        List<OrganizationPlanItem> items = items(200);
        OrganizationPlan plan = plan(items);
        Path path = temp.resolve("manifest.json");
        LibraryOperationCrashManifest writer = LibraryOperationCrashManifest.create(plan, path);
        byte[] original = Files.readAllBytes(path);
        long snapshotSize = original.length;
        for (int i = 0; i < 200; i++) {
            OrganizationPlanItem item = items.get(i);
            writer.itemState(item, OrganizationItemState.STAGING, "abc", "", null, "");
            writer.itemState(item, OrganizationItemState.VALIDATED, "abc", "def", null, "");
            writer.itemState(item, OrganizationItemState.COMMITTED, "abc", "def", null, "");
        }
        writer.operationState(OrganizationOperationState.COMPLETED, "done");
        assertThat(Files.readAllBytes(path)).isEqualTo(original);
        assertThat(Files.size(ManifestEventLog.path(path))).isLessThan(snapshotSize * 5);
        var parsed = new LibraryOperationCrashManifestReader().read(path);
        assertThat(parsed.schemaVersion()).isEqualTo(3);
        assertThat(parsed.state()).isEqualTo(OrganizationOperationState.COMPLETED);
        assertThat(parsed.message()).isEqualTo("done");
        assertThat(parsed.items()).hasSize(200).allSatisfy(item -> {
            assertThat(item.state()).isEqualTo(OrganizationItemState.COMMITTED);
            assertThat(item.sourceSha256()).isEqualTo("abc");
            assertThat(item.targetSha256()).isEqualTo("def");
        });
    }

    @Test
    void ignoresUnfinishedFinalFrameButRejectsCorruptedCompletedFrame() throws Exception {
        OrganizationPlanItem item = items(1).getFirst();
        Path path = temp.resolve("manifest.json");
        LibraryOperationCrashManifest writer = LibraryOperationCrashManifest.create(plan(List.of(item)), path);
        writer.itemState(item, OrganizationItemState.STAGING, "aa", "", null, "");
        Path events = ManifestEventLog.path(path);
        Files.writeString(events, "unfinished", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        var snapshot = new LibraryOperationCrashManifestReader().read(path);
        assertThat(snapshot.items()).singleElement().satisfies(state ->
                assertThat(state.state()).isEqualTo(OrganizationItemState.STAGING));
        Files.writeString(events, "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertThatThrownBy(() -> new LibraryOperationCrashManifestReader().read(path))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("framing");
    }

    @Test
    void readsLargeNewSchemaSnapshotButRetainsLegacySizeLimit() throws Exception {
        List<OrganizationPlanItem> all = items(10_000);
        Path path = temp.resolve("manifest.json");
        LibraryOperationCrashManifest.create(plan(all), path);
        assertThat(Files.size(path)).isGreaterThan(4L * 1024 * 1024);
        assertThat(new LibraryOperationCrashManifestReader().read(path).items()).hasSize(all.size());
    }

    @Test
    void missingSchemaThreeEventLogIsNotTreatedAsValidRecoveryEvidence() throws Exception {
        Path path = temp.resolve("manifest.json");
        LibraryOperationCrashManifest.create(plan(items(1)), path);
        Files.delete(ManifestEventLog.path(path));
        assertThatThrownBy(() -> new LibraryOperationCrashManifestReader().read(path))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void detectsChecksumMismatchEvenWhenFrameIsWellFormed() throws Exception {
        OrganizationPlanItem item = items(1).getFirst();
        Path path = temp.resolve("manifest.json");
        LibraryOperationCrashManifest writer = LibraryOperationCrashManifest.create(plan(List.of(item)), path);
        writer.itemState(item, OrganizationItemState.VALIDATED, "aa", "bb", null, "");
        Path log = ManifestEventLog.path(path);
        String original = Files.readString(log);
        Files.writeString(log, original.replace("VALIDATED", "COMMITTED"), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new LibraryOperationCrashManifestReader().read(path))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void batchingSkippedItemsReplaysEveryItemWithoutModifyingSnapshot() throws Exception {
        List<OrganizationPlanItem> skipped = new ArrayList<>();
        for (OrganizationPlanItem item : items(257)) {
            skipped.add(new OrganizationPlanItem(item.itemId(), item.sourcePath(), "", "unverified-preview-hash",
                    item.sourcePath(), "fb2", "fb2", OrganizationAction.KEEP_AS_IS, false,
                    "unchanged", DuplicateDecision.none(), 100, List.of(), List.of()));
        }
        Path path = temp.resolve("batch-manifest.json");
        LibraryOperationCrashManifest writer = LibraryOperationCrashManifest.create(plan(skipped), path);
        byte[] immutableSnapshot = Files.readAllBytes(path);
        for (int start = 0; start < skipped.size(); start += 128) {
            writer.skippedItems(skipped.subList(start, Math.min(start + 128, skipped.size())));
        }
        assertThat(Files.readAllBytes(path)).isEqualTo(immutableSnapshot);
        assertThat(ManifestEventLog.read(path)).hasSize(257);
        var parsed = new LibraryOperationCrashManifestReader().read(path);
        assertThat(parsed.items()).hasSize(257).allSatisfy(item -> {
            assertThat(item.state()).isEqualTo(OrganizationItemState.SKIPPED);
            assertThat(item.sourceSha256()).isBlank();
        });
    }

    @Test
    void batchRejectsMutatingItemWithoutWritingAnyPartialEvent() throws Exception {
        OrganizationPlanItem moving = items(1).getFirst();
        Path path = temp.resolve("invalid-batch-manifest.json");
        LibraryOperationCrashManifest writer = LibraryOperationCrashManifest.create(plan(List.of(moving)), path);
        assertThatThrownBy(() -> writer.skippedItems(List.of(moving)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-mutating");
        assertThat(Files.size(ManifestEventLog.path(path))).isZero();
        assertThat(new LibraryOperationCrashManifestReader().read(path).items())
                .singleElement().satisfies(item -> assertThat(item.state()).isEqualTo(OrganizationItemState.PLANNED));
    }

    private OrganizationPlan plan(List<OrganizationPlanItem> actions) {
        return new OrganizationPlan(UUID.randomUUID(), Instant.now(), OrganizerProfile.safeDefault(),
                temp.resolve("source"), temp.resolve("destination"), actions,
                PlanStatistics.from(actions), List.of());
    }

    private List<OrganizationPlanItem> items(int count) {
        List<OrganizationPlanItem> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = "sample-" + i + "-" + "name".repeat(16) + ".fb2";
            items.add(new OrganizationPlanItem(UUID.randomUUID(), temp.resolve("source").resolve(name), "", "",
                    temp.resolve("destination").resolve(name), "fb2", "fb2",
                    OrganizationAction.MOVE, false, "test", DuplicateDecision.none(), 100,
                    List.of(), List.of()));
        }
        return items;
    }
}
