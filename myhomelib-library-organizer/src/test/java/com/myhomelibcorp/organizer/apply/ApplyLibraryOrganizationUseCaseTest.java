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
            ApplyResult result = useCase(journal).apply(plan, () -> true);

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

    private ApplyLibraryOrganizationUseCase useCase(InMemoryJournal journal) {
        return useCase(journal, List.of());
    }

    private ApplyLibraryOrganizationUseCase useCase(InMemoryJournal journal, List<OrganizerCatalogPort> catalogs) {
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
