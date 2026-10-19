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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    void manualInspectionUsesVerifiedPrefixButStrictReadRejectsTornAndCorruptedFrames() throws Exception {
        OrganizationPlanItem item = items(1).getFirst();
        Path path = temp.resolve("manifest.json");
        LibraryOperationCrashManifest writer = LibraryOperationCrashManifest.create(plan(List.of(item)), path);
        writer.itemState(item, OrganizationItemState.STAGING, "aa", "", null, "");
        Path events = ManifestEventLog.path(path);
        Files.writeString(events, "unfinished", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertThatThrownBy(() -> new LibraryOperationCrashManifestReader().read(path))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("incomplete");
        var snapshot = new LibraryOperationCrashManifestReader().readForManualInspection(path);
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

    @Test
    void refusesToOverwriteExistingRecoveryEvidence() throws Exception {
        OrganizationPlanItem item = items(1).getFirst();
        Path path = temp.resolve("existing.json");
        LibraryOperationCrashManifest writer = LibraryOperationCrashManifest.create(plan(List.of(item)), path);
        writer.itemState(item, OrganizationItemState.STAGING, "source", "", null, "in-flight");
        byte[] snapshot = Files.readAllBytes(path);
        byte[] events = Files.readAllBytes(ManifestEventLog.path(path));
        assertThatThrownBy(() -> LibraryOperationCrashManifest.create(plan(items(2)), path))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readAllBytes(path)).isEqualTo(snapshot);
        assertThat(Files.readAllBytes(ManifestEventLog.path(path))).isEqualTo(events);
    }

    @Test
    void orphanedEventLogBlocksNewSnapshotBeforeAnyWrite() throws Exception {
        Path path = temp.resolve("orphan.json");
        Path events = ManifestEventLog.path(path);
        Files.writeString(events, "previous recovery evidence");
        assertThatThrownBy(() -> LibraryOperationCrashManifest.create(plan(items(1)), path))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.exists(path)).isFalse();
        assertThat(Files.readString(events)).isEqualTo("previous recovery evidence");
    }

    @Test
    void duplicateIdsInPlanAreRejectedBeforeManifestCreation() {
        OrganizationPlanItem item = items(1).getFirst();
        Path path = temp.resolve("duplicates.json");
        assertThatThrownBy(() -> LibraryOperationCrashManifest.create(plan(List.of(item, item)), path))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate item id");
        assertThat(Files.exists(path)).isFalse();
    }

    @Test
    void foreignOrChangedItemIdentityCannotPoisonRecoveryLog() throws Exception {
        OrganizationPlanItem original = items(1).getFirst();
        Path path = temp.resolve("identity.json");
        LibraryOperationCrashManifest writer = LibraryOperationCrashManifest.create(plan(List.of(original)), path);
        OrganizationPlanItem changed = new OrganizationPlanItem(original.itemId(),
                temp.resolve("foreign.fb2"), original.sourceEntry(), original.expectedSourceSha256(),
                original.targetPath(), original.sourceFormat(), original.targetFormat(), original.action(),
                original.conversionRequired(), original.reason(), original.duplicate(), original.confidence(),
                original.warnings(), original.issues());
        assertThatThrownBy(() -> writer.itemState(changed, OrganizationItemState.COMMITTED,
                "", "", null, ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity");
        assertThat(Files.size(ManifestEventLog.path(path))).isZero();
    }

    @Test
    void refusesAppendAfterTornTailToPreserveRecoveryEvidence() throws Exception {
        Path path = temp.resolve("torn.json");
        LibraryOperationCrashManifest writer = LibraryOperationCrashManifest.create(plan(items(1)), path);
        writer.operationState(OrganizationOperationState.APPLYING, "started");
        Path events = ManifestEventLog.path(path);
        Files.writeString(events, "incomplete-frame", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        byte[] before = Files.readAllBytes(events);
        assertThatThrownBy(() -> writer.operationState(OrganizationOperationState.COMPLETED, "done"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unfinished event");
        assertThat(Files.readAllBytes(events)).isEqualTo(before);
        assertThatThrownBy(() -> new LibraryOperationCrashManifestReader().read(path))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("incomplete");
        assertThat(new LibraryOperationCrashManifestReader().readForManualInspection(path).state())
                .isEqualTo(OrganizationOperationState.APPLYING);
    }

    @Test
    void rejectsMultilineEventBeforeAnyMutation() throws Exception {
        Path path = temp.resolve("multiline.json");
        LibraryOperationCrashManifest.create(plan(items(1)), path);
        assertThatThrownBy(() -> ManifestEventLog.appendBatch(path,
                List.of("{\"event\":\"safe\"}", "{\n\"bad\":true}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one JSON line");
        assertThat(Files.size(ManifestEventLog.path(path))).isZero();
    }

    @Test
    void parallelBatchesNeverInterleaveFrames() throws Exception {
        Path path = temp.resolve("parallel.json");
        LibraryOperationCrashManifest.create(plan(items(1)), path);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int thread = 0; thread < 6; thread++) {
                final int index = thread;
                tasks.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 40; i++) {
                            ManifestEventLog.appendBatch(path, List.of(
                                    "{\"type\":\"stress\",\"thread\":" + index + ",\"i\":" + i + "}",
                                    "{\"type\":\"stress\",\"thread\":" + index + ",\"i\":" + i + "}"));
                        }
                    } catch (Exception error) { throw new RuntimeException(error); }
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(30, TimeUnit.SECONDS);
        }
        List<String> events = ManifestEventLog.read(path);
        assertThat(events).hasSize(6 * 40 * 2);
        for (int i = 0; i < events.size(); i += 2) {
            assertThat(events.get(i)).isEqualTo(events.get(i + 1));
        }
    }

    @Test
    void hardKilledWriterWithDurableTornEventMustNotBeTrustedAsCompleteRecovery() throws Exception {
        Path path = temp.resolve("kill-point.json");
        LibraryOperationCrashManifest writer = LibraryOperationCrashManifest.create(plan(items(1)), path);
        writer.operationState(OrganizationOperationState.APPLYING, "started");
        Path eventLog = ManifestEventLog.path(path);
        byte[] originalSnapshot = Files.readAllBytes(path);
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String testClasses = Path.of(System.getProperty("user.dir"), "target", "test-classes").toString();
        Process child = new ProcessBuilder(javaExecutable, "-cp", testClasses,
                OrganizerManifestKillWorker.class.getName(), eventLog.toString())
                .redirectErrorStream(true).start();
        assertThat(child.waitFor(15, TimeUnit.SECONDS)).isTrue();
        assertThat(child.exitValue()).isEqualTo(83);
        assertThat(Files.readAllBytes(path)).isEqualTo(originalSnapshot);
        assertThat(Files.readString(eventLog)).contains("\"type\":\"operation\"");
        assertThatThrownBy(() -> new LibraryOperationCrashManifestReader().read(path))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomplete");
        assertThat(new LibraryOperationCrashManifestReader().readForManualInspection(path).state())
                .isEqualTo(OrganizationOperationState.APPLYING);
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
