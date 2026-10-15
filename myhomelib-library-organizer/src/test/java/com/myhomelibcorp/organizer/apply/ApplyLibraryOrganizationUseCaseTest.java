package com.myhomelibcorp.organizer.apply;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.epub.optimizer.EpubOptimizer;
import com.myhomelibcorp.organizer.duplicate.DuplicateDecision;
import com.myhomelibcorp.organizer.fs.LibraryOperationWorkspace;
import com.myhomelibcorp.organizer.journal.OrganizationItemState;
import com.myhomelibcorp.organizer.journal.OrganizationJournalItem;
import com.myhomelibcorp.organizer.journal.OrganizationJournalOperation;
import com.myhomelibcorp.organizer.journal.OrganizationJournalPort;
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
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplyLibraryOrganizationUseCaseTest {
    @TempDir Path temp;

    @Test
    void verifiedMovePublishesTargetBeforeRemovingSourceAndCommitsJournal() throws Exception {
        Path sourceRoot = Files.createDirectories(temp.resolve("source"));
        Path destinationRoot = temp.resolve("library");
        Path source = sourceRoot.resolve("old.fb2");
        byte[] bytes = "verified source".getBytes(StandardCharsets.UTF_8);
        Files.write(source, bytes);
        Path target = destinationRoot.resolve("Автор/Книга.fb2");
        String sourceHash = sha256(source);
        UUID operationId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        OrganizationPlanItem item = new OrganizationPlanItem(itemId, source, "", sourceHash, target,
                "fb2", "fb2", OrganizationAction.MOVE_AND_RENAME, false,
                "Canonical directory and filename differ", DuplicateDecision.none(), 100, List.of(), List.of());
        OrganizationPlan plan = new OrganizationPlan(operationId, Instant.now(), OrganizerProfile.safeDefault(),
                sourceRoot, destinationRoot, List.of(item), PlanStatistics.from(List.of(item)), List.of());
        InMemoryJournal journal = new InMemoryJournal();

        ApplyResult result = useCase(journal).apply(plan, () -> false);

        assertThat(result.committed()).isEqualTo(1);
        assertThat(Files.exists(source)).isFalse();
        assertThat(Files.readAllBytes(target)).isEqualTo(bytes);
        assertThat(journal.operation(operationId).state()).isEqualTo(OrganizationOperationState.COMPLETED);
        assertThat(journal.items(operationId)).singleElement()
                .satisfies(snapshot -> {
                    assertThat(snapshot.state()).isEqualTo(OrganizationItemState.COMMITTED);
                    assertThat(snapshot.sourceSha256()).isEqualToIgnoringCase(sourceHash);
                    assertThat(snapshot.targetSha256()).isEqualToIgnoringCase(sourceHash);
                });
    }

    @Test
    void sourceChangedAfterPreviewFailsPreflightWithoutPublishingOrStartingJournal() throws Exception {
        Path sourceRoot = Files.createDirectories(temp.resolve("source"));
        Path destinationRoot = temp.resolve("library");
        Path source = sourceRoot.resolve("book.fb2");
        Files.writeString(source, "preview version", StandardCharsets.UTF_8);
        String expectedHash = sha256(source);
        Path target = destinationRoot.resolve("book.fb2");
        UUID operationId = UUID.randomUUID();
        OrganizationPlanItem item = new OrganizationPlanItem(UUID.randomUUID(), source, "", expectedHash, target,
                "fb2", "fb2", OrganizationAction.MOVE, false, "Canonical directory differs",
                DuplicateDecision.none(), 100, List.of(), List.of());
        OrganizationPlan plan = new OrganizationPlan(operationId, Instant.now(), OrganizerProfile.safeDefault(),
                sourceRoot, destinationRoot, List.of(item), PlanStatistics.from(List.of(item)), List.of());
        Files.writeString(source, "changed after preview", StandardCharsets.UTF_8);
        InMemoryJournal journal = new InMemoryJournal();

        assertThatThrownBy(() -> useCase(journal).apply(plan, () -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Source changed since Analyze/Preview");

        assertThat(Files.readString(source, StandardCharsets.UTF_8)).isEqualTo("changed after preview");
        assertThat(Files.exists(target)).isFalse();
        assertThat(journal.operation(operationId)).isNull();
    }



    @Test
    void refusesToMoveFileChangedAfterPreflightDespiteReusingItsVerifiedPlanHash() throws Exception {
        Path sourceRoot = Files.createDirectories(temp.resolve("changed-after-preflight-source"));
        Path destinationRoot = temp.resolve("changed-after-preflight-library");
        Path source = sourceRoot.resolve("book.fb2");
        Files.writeString(source, "preview bytes", StandardCharsets.UTF_8);
        Path target = destinationRoot.resolve("book.fb2");
        OrganizationPlanItem item = new OrganizationPlanItem(UUID.randomUUID(), source, "", sha256(source),
                target, "fb2", "fb2", OrganizationAction.MOVE, false, "Move",
                DuplicateDecision.none(), 100, List.of(), List.of());
        OrganizationPlan plan = new OrganizationPlan(UUID.randomUUID(), Instant.now(), OrganizerProfile.safeDefault(),
                sourceRoot, destinationRoot, List.of(item), PlanStatistics.from(List.of(item)), List.of());
        InMemoryJournal delegate = new InMemoryJournal();
        OrganizationJournalPort changingJournal = new OrganizationJournalPort() {
            @Override public void begin(OrganizationJournalOperation operation, List<OrganizationJournalItem> items) {
                delegate.begin(operation, items);
                try { Files.writeString(source, "modified after preflight", StandardCharsets.UTF_8); }
                catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            }
            @Override public void updateOperation(UUID id, OrganizationOperationState state, String summary) {
                delegate.updateOperation(id, state, summary);
            }
            @Override public void updateItem(OrganizationJournalItem updated) { delegate.updateItem(updated); }
            @Override public List<OrganizationJournalOperation> incompleteOperations() { return delegate.incompleteOperations(); }
            @Override public List<OrganizationJournalItem> items(UUID id) { return delegate.items(id); }
        };
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        EpubOptimizer optimizer = new EpubOptimizer(null, null, null, null, null, null, null, null, null, List.of());
        OrganizerOutputValidationPort validation = (file, format, maxBytes) -> { };
        ApplyLibraryOrganizationUseCase useCase = new ApplyLibraryOrganizationUseCase(
                List.of(), changingJournal, coordinator, List.of(), optimizer, settings(), validation);

        assertThatThrownBy(() -> useCase.apply(plan, () -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Source hash mismatch before copy");
        assertThat(Files.readString(source)).isEqualTo("modified after preflight");
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    void skippingExistingBookDoesNotNeedToRereadContentAfterAnalyze() throws Exception {
        Path sourceRoot = Files.createDirectories(temp.resolve("skip-source"));
        Path destinationRoot = temp.resolve("skip-library");
        Path source = sourceRoot.resolve("keep.fb2");
        Files.writeString(source, "at Analyze time", StandardCharsets.UTF_8);
        String analyzedHash = sha256(source);
        // A book that is not touched by Apply may have changed since Analyze.
        Files.writeString(source, "updated by another application", StandardCharsets.UTF_8);
        OrganizationPlanItem item = new OrganizationPlanItem(UUID.randomUUID(), source, "", analyzedHash,
                source, "fb2", "fb2", OrganizationAction.KEEP_AS_IS, false, "Already organized",
                DuplicateDecision.none(), 100, List.of(), List.of());
        OrganizationPlan plan = new OrganizationPlan(UUID.randomUUID(), Instant.now(), OrganizerProfile.safeDefault(),
                sourceRoot, destinationRoot, List.of(item), PlanStatistics.from(List.of(item)), List.of());
        ApplyResult result = useCase(new InMemoryJournal()).apply(plan, () -> false);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.committed()).isEqualTo(0);
        assertThat(Files.readString(source)).isEqualTo("updated by another application");
        assertThat(destinationRoot).doesNotExist();
    }

    @Test
    void skipsLargeAlreadyOrganizedLibraryInBoundedJournalBatches() throws Exception {
        Path sourceRoot = Files.createDirectories(temp.resolve("large-already-organized"));
        Path destinationRoot = temp.resolve("large-result");
        List<OrganizationPlanItem> items = new ArrayList<>();
        for (int i = 0; i < 257; i++) {
            Path source = sourceRoot.resolve("book-" + i + ".fb2");
            Files.writeString(source, "original-" + i, StandardCharsets.UTF_8);
            items.add(new OrganizationPlanItem(UUID.randomUUID(), source, "", "not-freshly-verified",
                    source, "fb2", "fb2", OrganizationAction.KEEP_AS_IS, false, "already organized",
                    DuplicateDecision.none(), 100, List.of(), List.of()));
        }
        OrganizationPlan plan = new OrganizationPlan(UUID.randomUUID(), Instant.now(), OrganizerProfile.safeDefault(),
                sourceRoot, destinationRoot, items, PlanStatistics.from(items), List.of());
        InMemoryJournal delegate = new InMemoryJournal();
        java.util.concurrent.atomic.AtomicInteger batches = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<Integer> batchSizes = new java.util.ArrayList<>();
        OrganizationJournalPort journal = new OrganizationJournalPort() {
            @Override public void begin(OrganizationJournalOperation op, List<OrganizationJournalItem> snapshots) {
                delegate.begin(op, snapshots);
            }
            @Override public void updateOperation(UUID id, OrganizationOperationState state, String summary) {
                delegate.updateOperation(id, state, summary);
            }
            @Override public void updateItem(OrganizationJournalItem snapshot) {
                throw new AssertionError("A skipped book must not commit an individual DB transaction");
            }
            @Override public void updateItems(List<OrganizationJournalItem> snapshots) {
                batches.incrementAndGet();
                batchSizes.add(snapshots.size());
                delegate.updateItems(snapshots);
            }
            @Override public List<OrganizationJournalOperation> incompleteOperations() {
                return delegate.incompleteOperations();
            }
            @Override public List<OrganizationJournalItem> items(UUID id) { return delegate.items(id); }
        };
        ApplyResult result = useCase(journal).apply(plan, () -> false);
        assertThat(result.skipped()).isEqualTo(257);
        assertThat(result.committed()).isZero();
        assertThat(batches).hasValue(3);
        assertThat(batchSizes).containsExactly(128, 128, 1);
        assertThat(delegate.items(plan.operationId())).hasSize(257).allSatisfy(snapshot -> {
            assertThat(snapshot.state()).isEqualTo(OrganizationItemState.SKIPPED);
            assertThat(snapshot.sourceSha256()).isBlank();
            assertThat(Files.exists(snapshot.sourcePath())).isTrue();
        });
        assertThat(delegate.operation(plan.operationId()).state()).isEqualTo(OrganizationOperationState.COMPLETED);
        assertThat(destinationRoot).doesNotExist();
    }

    @Test
    void skippedBookNeverAttemptsStagingCleanup() throws Exception {
        Path sourceRoot = Files.createDirectories(temp.resolve("skip-no-stage-source"));
        Path source = sourceRoot.resolve("keep.fb2");
        Files.writeString(source, "untouched");
        OrganizationPlanItem skip = new OrganizationPlanItem(UUID.randomUUID(), source, "", "preview-hash",
                source, "fb2", "fb2", OrganizationAction.KEEP_AS_IS, false, "keep",
                DuplicateDecision.none(), 100, List.of(), List.of());
        UUID operationId = UUID.randomUUID();
        OrganizationPlan plan = new OrganizationPlan(operationId, Instant.now(), OrganizerProfile.safeDefault(),
                sourceRoot, temp.resolve("skip-no-stage-target"), List.of(skip), PlanStatistics.from(List.of(skip)), List.of());
        Path workspaceMarker = LibraryOperationWorkspace.rootFor(operationId)
                .resolve("output").resolve(skip.itemId() + "-marker.tmp");
        java.util.concurrent.atomic.AtomicBoolean checked = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            ApplyResult result = useCase(new InMemoryJournal()).apply(plan, () -> false, progress -> {
                if (progress.phase() == OrganizationApplyProgress.Phase.APPLYING && source.equals(progress.currentPath()) && progress.processed() == 0) {
                    try { Files.writeString(workspaceMarker, "must remain until final workspace cleanup"); }
                    catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                }
                if (progress.processed() == 1 && source.equals(progress.currentPath())) {
                    checked.set(true);
                    assertThat(workspaceMarker).exists();
                }
            });
            assertThat(result.skipped()).isEqualTo(1);
            assertThat(checked).isTrue();
            assertThat(Files.readString(source)).isEqualTo("untouched");
        } finally {
            // On success, workspace.cleanup() removes the marker. On failure it may be
            // retained as recovery evidence, so clean up only the test-owned path.
            LibraryOperationWorkspace.deleteTree(LibraryOperationWorkspace.rootFor(operationId));
        }
    }

    @Test
    void mixedSkippedAndExtractedBooksPreserveParallelStagingWindow() throws Exception {
        Path sourceRoot = Files.createDirectories(temp.resolve("mixed-stage-source"));
        Path destinationRoot = temp.resolve("mixed-stage-library");
        List<OrganizationPlanItem> items = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Path source = sourceRoot.resolve("skip-" + i + ".fb2");
            Files.writeString(source, "keep " + i);
            items.add(new OrganizationPlanItem(UUID.randomUUID(), source, "", "not-rechecked",
                    source, "fb2", "fb2", OrganizationAction.NOOP, false, "already sorted",
                    DuplicateDecision.none(), 100, List.of(), List.of()));
        }
        Path archive = sourceRoot.resolve("archive.zip");
        byte[] content = "book extracted safely from the ZIP archive".getBytes(StandardCharsets.UTF_8);
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("embedded.fb2"));
            zip.write(content);
            zip.closeEntry();
        }
        Path target = destinationRoot.resolve("embedded.fb2");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String entryHash = HexFormat.of().formatHex(digest.digest(content));
        OrganizationPlanItem extracted = new OrganizationPlanItem(UUID.randomUUID(), archive, "embedded.fb2",
                entryHash, target, "fb2zip", "fb2", OrganizationAction.EXTRACT, false, "extract",
                DuplicateDecision.none(), 100, List.of(), List.of());
        items.add(extracted);
        OrganizationPlan plan = new OrganizationPlan(UUID.randomUUID(), Instant.now(), OrganizerProfile.safeDefault(),
                sourceRoot, destinationRoot, items, PlanStatistics.from(items), List.of());
        ApplyResult result = useCase(new InMemoryJournal()).apply(plan, () -> false);
        assertThat(result.skipped()).isEqualTo(40);
        assertThat(result.committed()).isEqualTo(1);
        assertThat(Files.readAllBytes(target)).isEqualTo(content);
        assertThat(Files.exists(archive)).isTrue();
        for (int i = 0; i < 40; i++) {
            assertThat(Files.readString(sourceRoot.resolve("skip-" + i + ".fb2"))).isEqualTo("keep " + i);
        }
    }

    @Test
    void cancellationFlushesPendingSkippedBooksWithoutTouchingAnyFile() throws Exception {
        Path sourceRoot = Files.createDirectories(temp.resolve("cancel-skip-source"));
        List<OrganizationPlanItem> items = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Path file = sourceRoot.resolve("book-" + i + ".fb2");
            Files.writeString(file, "unchanged-" + i);
            items.add(new OrganizationPlanItem(UUID.randomUUID(), file, "", "preview-only", file,
                    "fb2", "fb2", OrganizationAction.KEEP_AS_IS, false, "skip",
                    DuplicateDecision.none(), 100, List.of(), List.of()));
        }
        OrganizationPlan plan = new OrganizationPlan(UUID.randomUUID(), Instant.now(), OrganizerProfile.safeDefault(),
                sourceRoot, temp.resolve("no-destination"), items, PlanStatistics.from(items), List.of());
        InMemoryJournal journal = new InMemoryJournal();
        java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            ApplyResult result = useCase(journal).apply(plan, cancel::get, progress -> {
                if (progress.phase() == OrganizationApplyProgress.Phase.APPLYING && progress.processed() == 5) cancel.set(true);
            });
            assertThat(result.cancelled()).isTrue();
            assertThat(result.skipped()).isEqualTo(5);
            List<OrganizationJournalItem> snapshots = journal.items(plan.operationId());
            assertThat(snapshots.stream().filter(item -> item.state() == OrganizationItemState.SKIPPED).count())
                    .isEqualTo(5);
            assertThat(snapshots.stream().filter(item -> item.state() == OrganizationItemState.PLANNED).count())
                    .isEqualTo(5);
            for (int i = 0; i < 10; i++) {
                assertThat(Files.readString(sourceRoot.resolve("book-" + i + ".fb2")))
                        .isEqualTo("unchanged-" + i);
            }
            var recovered = new com.myhomelibcorp.organizer.fs.LibraryOperationCrashManifestReader()
                    .read(LibraryOperationWorkspace.manifestFor(plan.operationId()));
            assertThat(recovered.items().stream().filter(item -> item.state() == OrganizationItemState.SKIPPED).count())
                    .isEqualTo(5);
        } finally {
            LibraryOperationWorkspace.deleteTree(LibraryOperationWorkspace.rootFor(plan.operationId()));
        }
    }

    @Test
    void collectionMismatchFailsBeforePreflightJournalOrWorkspaceMutation() throws Exception {
        UUID operationId = UUID.randomUUID();
        Path sourceRoot = temp.resolve("mismatch-source");
        Path destinationRoot = temp.resolve("mismatch-destination");
        OrganizationPlan plan = new OrganizationPlan(operationId, Instant.now(), OrganizerProfile.safeDefault(),
                sourceRoot, destinationRoot, List.of(), PlanStatistics.from(List.of()), List.of(), "collection-A");
        InMemoryJournal journal = new InMemoryJournal();
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        CollectionLifecyclePort lifecycle = lifecycle("collection-B", temp.resolve("collection-b"));
        EpubOptimizer unusedOptimizer = new EpubOptimizer(null, null, null, null, null, null, null, null, null, List.of());
        OrganizerOutputValidationPort validation = (file, format, maxBytes) -> { };
        ApplyLibraryOrganizationUseCase useCase = new ApplyLibraryOrganizationUseCase(
                List.of(), journal, coordinator, List.of(), unusedOptimizer, settings(), validation, lifecycle);

        assertThatThrownBy(() -> useCase.apply(plan, () -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Active collection changed");

        assertThat(journal.operation(operationId)).isNull();
        assertThat(coordinator.activeOperation()).isNull();
        assertThat(Files.exists(LibraryOperationWorkspace.rootFor(operationId))).isFalse();
    }

    @Test
    void catalogFailureDoesNotDeleteTargetReplacedAfterPublication() throws Exception {
        Path sourceRoot = Files.createDirectories(temp.resolve("catalog-source"));
        Path destinationRoot = temp.resolve("catalog-library");
        Path source = sourceRoot.resolve("book.fb2");
        Files.writeString(source, "source payload", StandardCharsets.UTF_8);
        String sourceHash = sha256(source);
        Path target = destinationRoot.resolve("book.fb2");
        UUID operationId = UUID.randomUUID();
        OrganizationPlanItem item = new OrganizationPlanItem(UUID.randomUUID(), source, "", sourceHash, target,
                "fb2", "fb2", OrganizationAction.MOVE, false, "Canonical directory differs",
                DuplicateDecision.none(), 100, List.of(), List.of());
        OrganizationPlan plan = new OrganizationPlan(operationId, Instant.now(), OrganizerProfile.safeDefault(),
                sourceRoot, destinationRoot, List.of(item), PlanStatistics.from(List.of(item)), List.of());
        InMemoryJournal journal = new InMemoryJournal();
        OrganizerCatalogPort replacingCatalog = new OrganizerCatalogPort() {
            @Override public boolean supports(OrganizationPlanItem ignored) { return true; }
            @Override public void afterFilesystemCommit(OrganizationPlanItem committed) throws Exception {
                Files.writeString(committed.targetPath(), "foreign replacement", StandardCharsets.UTF_8);
                throw new IllegalStateException("catalog failure");
            }
            @Override public void rollbackCatalog(OrganizationPlanItem ignored) { }
        };

        assertThatThrownBy(() -> useCase(journal, List.of(replacingCatalog)).apply(plan, () -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("catalog failure")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .anySatisfy(suppressed -> assertThat(suppressed.getMessage()).contains("Retained target")));

        assertThat(Files.readString(source, StandardCharsets.UTF_8)).isEqualTo("source payload");
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("foreign replacement");
        assertThat(journal.operation(operationId).state()).isEqualTo(OrganizationOperationState.FAILED);
    }

    @Test
    void cancellationBeforeFirstCommitKeepsSourceAndRecoveryEvidence() throws Exception {
        Path sourceRoot = Files.createDirectories(temp.resolve("cancel-source"));
        Path destinationRoot = temp.resolve("cancel-library");
        Path source = sourceRoot.resolve("book.fb2");
        Files.writeString(source, "unchanged", StandardCharsets.UTF_8);
        String expectedHash = sha256(source);
        Path target = destinationRoot.resolve("book.fb2");
        UUID operationId = UUID.randomUUID();
        OrganizationPlanItem item = new OrganizationPlanItem(UUID.randomUUID(), source, "", expectedHash, target,
                "fb2", "fb2", OrganizationAction.MOVE, false, "Canonical directory differs",
                DuplicateDecision.none(), 100, List.of(), List.of());
        OrganizationPlan plan = new OrganizationPlan(operationId, Instant.now(), OrganizerProfile.safeDefault(),
                sourceRoot, destinationRoot, List.of(item), PlanStatistics.from(List.of(item)), List.of());
        InMemoryJournal journal = new InMemoryJournal();

        try {
            java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean();
            ApplyResult result = useCase(journal).apply(plan, cancel::get, progress -> {
                if (progress.phase() == OrganizationApplyProgress.Phase.APPLYING) cancel.set(true);
            });

            assertThat(result.cancelled()).isTrue();
            assertThat(Files.readString(source, StandardCharsets.UTF_8)).isEqualTo("unchanged");
            assertThat(Files.exists(target)).isFalse();
            assertThat(journal.operation(operationId).state()).isEqualTo(OrganizationOperationState.CANCELLED);
            assertThat(journal.items(operationId)).singleElement()
                    .satisfies(snapshot -> assertThat(snapshot.state()).isEqualTo(OrganizationItemState.PLANNED));
            assertThat(Files.isRegularFile(LibraryOperationWorkspace.manifestFor(operationId))).isTrue();
        } finally {
            LibraryOperationWorkspace.deleteTree(LibraryOperationWorkspace.rootFor(operationId));
        }
    }

    @Test
    void preflightCancellationReportsProgressWithoutJournalOrFileMutation() throws Exception {
        Path sourceRoot = Files.createDirectory(temp.resolve("preflight-source"));
        Path destination = temp.resolve("preflight-target");
        List<OrganizationPlanItem> items = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Path source = Files.writeString(sourceRoot.resolve(i + ".fb2"), "original-" + i);
            items.add(new OrganizationPlanItem(UUID.randomUUID(), source, "", sha256(source), destination.resolve(i + ".fb2"),
                    "fb2", "fb2", OrganizationAction.MOVE, false, "move", DuplicateDecision.none(), 100, List.of(), List.of()));
        }
        OrganizationPlan plan = new OrganizationPlan(UUID.randomUUID(), Instant.now(), OrganizerProfile.safeDefault(),
                sourceRoot, destination, items, PlanStatistics.from(items), List.of());
        InMemoryJournal journal = new InMemoryJournal();
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        List<OrganizationApplyProgress> snapshots = new ArrayList<>();
        ApplyResult result = useCase(journal).apply(plan, cancelled::get, progress -> {
            snapshots.add(progress);
            if (progress.phase() == OrganizationApplyProgress.Phase.PREFLIGHT && progress.checked() == 1) cancelled.set(true);
        });
        assertThat(result.cancelled()).isTrue();
        assertThat(result.committed()).isZero();
        assertThat(snapshots).hasSize(2).allMatch(p -> p.phase() == OrganizationApplyProgress.Phase.PREFLIGHT);
        assertThat(journal.items(plan.operationId())).isEmpty();
        assertThat(journal.incompleteOperations()).isEmpty();
        assertThat(destination).doesNotExist();
        assertThat(LibraryOperationWorkspace.rootFor(plan.operationId())).doesNotExist();
        assertThat(Files.readString(sourceRoot.resolve("0.fb2"))).isEqualTo("original-0");
        assertThat(Files.readString(sourceRoot.resolve("1.fb2"))).isEqualTo("original-1");
    }

    private ApplyLibraryOrganizationUseCase useCase(OrganizationJournalPort journal) {
        return useCase(journal, List.of());
    }

    private ApplyLibraryOrganizationUseCase useCase(OrganizationJournalPort journal, List<OrganizerCatalogPort> catalogs) {
        EpubOptimizer unusedOptimizer = new EpubOptimizer(null, null, null, null, null, null, null, null, null, List.of());
        OrganizerOutputValidationPort validation = (file, format, maxBytes) -> { };
        return new ApplyLibraryOrganizationUseCase(List.of(), journal, new LibraryOperationCoordinator(), catalogs,
                unusedOptimizer, settings(), validation);
    }


    private CollectionLifecyclePort lifecycle(String id, Path root) {
        Collection current = new Collection(id, id, root, "collection.db", 0, null, null, null, null);
        return new CollectionLifecyclePort() {
            @Override public void switchToCollection(Collection collection) { }
            @Override public void closeCurrentCollection() { }
            @Override public Collection getCurrentCollection() { return current; }
            @Override public void updateCurrentCollection(Collection collection) { }
            @Override public boolean hasActiveCollection() { return true; }
            @Override public boolean isCollectionReady() { return true; }
            @Override public long getDatabaseSize() { return 0; }
        };
    }

    private ApplicationSettingsPort settings() {
        return new ApplicationSettingsPort() {
            @Override public String get(String key, String defaultValue) { return defaultValue; }
            @Override public void put(String key, String value) { }
            @Override public void remove(String key) { }
            @Override public Map<String, String> findByPrefix(String prefix) { return Map.of(); }
        };
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(path));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static final class InMemoryJournal implements OrganizationJournalPort {
        private final Map<UUID, OrganizationJournalOperation> operations = new LinkedHashMap<>();
        private final Map<UUID, LinkedHashMap<UUID, OrganizationJournalItem>> items = new LinkedHashMap<>();

        @Override public void begin(OrganizationJournalOperation operation, List<OrganizationJournalItem> snapshots) {
            operations.put(operation.operationId(), operation);
            LinkedHashMap<UUID, OrganizationJournalItem> byId = new LinkedHashMap<>();
            for (OrganizationJournalItem snapshot : snapshots) byId.put(snapshot.itemId(), snapshot);
            items.put(operation.operationId(), byId);
        }

        @Override public void updateOperation(UUID operationId, OrganizationOperationState state, String summaryJson) {
            OrganizationJournalOperation current = operations.get(operationId);
            if (current == null) throw new IllegalStateException("Unknown operation " + operationId);
            operations.put(operationId, new OrganizationJournalOperation(current.operationId(), current.profileId(),
                    current.sourceRoot(), current.destinationRoot(), state, current.startedAt(),
                    state == OrganizationOperationState.COMPLETED || state == OrganizationOperationState.ROLLED_BACK
                            ? Instant.now() : current.completedAt(), summaryJson));
        }

        @Override public void updateItem(OrganizationJournalItem item) {
            items.computeIfAbsent(item.operationId(), ignored -> new LinkedHashMap<>()).put(item.itemId(), item);
        }

        @Override public List<OrganizationJournalOperation> incompleteOperations() {
            return operations.values().stream()
                    .filter(operation -> operation.state() != OrganizationOperationState.COMPLETED
                            && operation.state() != OrganizationOperationState.ROLLED_BACK)
                    .toList();
        }

        @Override public List<OrganizationJournalItem> items(UUID operationId) {
            return new ArrayList<>(items.getOrDefault(operationId, new LinkedHashMap<>()).values());
        }

        OrganizationJournalOperation operation(UUID id) { return operations.get(id); }
    }
}
