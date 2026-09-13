package com.myhomelibcorp.application.health;

import com.myhomelibcorp.application.integrity.ArtifactIntegrityFinding;
import com.myhomelibcorp.application.integrity.ArtifactIntegrityReport;
import com.myhomelibcorp.application.integrity.ArtifactIntegrityStatus;
import com.myhomelibcorp.application.operation.LibraryOperationConflictException;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.backup.CollectionBackupPort;
import com.myhomelibcorp.application.port.out.integrity.ArtifactIntegrityPort;
import com.myhomelibcorp.application.port.out.integrity.DataIntegrityPort;
import com.myhomelibcorp.application.port.out.search.SearchIndexLifecycle;
import com.myhomelibcorp.application.search.SearchIndexHealth;
import com.myhomelibcorp.application.usecase.integrity.DataIntegrityChecker;
import com.myhomelibcorp.application.usecase.integrity.IntegrityReport;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LibraryHealthServiceTest {

    @Test
    void combinesArtifactMetadataIndexAndBackupKpisIntoActionableIssues() {
        Instant now = Instant.now();
        ArtifactIntegrityPort artifacts = ignored -> new ArtifactIntegrityReport(
                10, 10, 7, 7, 1, 1, 1, 2048,
                List.of(new ArtifactIntegrityFinding("a1", "b1", "Book", "/book.fb2",
                        ArtifactIntegrityStatus.MISSING, 100, -1, "", "", "missing", now)), now);
        DataIntegrityPort integrityPort = () -> new IntegrityReport(
                List.of("metadata", "lucene"), 2, 1, 0, 0, 3,
                0, 1, 0, true, "ok", false, 100, 98);
        DataIntegrityChecker checker = new DataIntegrityChecker(integrityPort);

        Collection collection = mock(Collection.class);
        when(collection.getId()).thenReturn("c1");
        CollectionBackupPort backup = mock(CollectionBackupPort.class);
        when(backup.getCurrentCollection()).thenReturn(collection);
        when(backup.latestBackupTime(collection)).thenReturn(Optional.of(now.minusSeconds(10 * 24 * 3600L)));

        SearchIndexLifecycle search = mock(SearchIndexLifecycle.class);
        when(search.currentHealth()).thenReturn(new SearchIndexHealth(false, "DIRTY"));

        LibraryHealthReport report = new LibraryHealthService(checker, artifacts, backup, search, new LibraryOperationCoordinator(), 168).refresh();

        assertThat(report.missingArtifacts()).isEqualTo(1);
        assertThat(report.corruptArtifacts()).isEqualTo(1);
        assertThat(report.changedArtifacts()).isEqualTo(1);
        assertThat(report.duplicateBooks()).isEqualTo(3);
        assertThat(report.metadataGaps()).isEqualTo(4);
        assertThat(report.searchIndexFresh()).isFalse();
        assertThat(report.backupStale()).isTrue();
        assertThat(report.issues()).extracting(LibraryHealthIssue::type).contains(
                LibraryHealthIssueType.MISSING_ARTIFACTS,
                LibraryHealthIssueType.CORRUPT_ARTIFACTS,
                LibraryHealthIssueType.CHANGED_ARTIFACTS,
                LibraryHealthIssueType.DUPLICATES,
                LibraryHealthIssueType.METADATA_GAPS,
                LibraryHealthIssueType.STALE_SEARCH_INDEX,
                LibraryHealthIssueType.BACKUP_AGE);
    }
    @Test
    void auditCannotRaceAnActiveCollectionSwitch() {
        ArtifactIntegrityPort artifacts = ignored -> new ArtifactIntegrityReport(
                0, 0, 0, 0, 0, 0, 0, 0, List.of(), Instant.now());
        DataIntegrityChecker checker = new DataIntegrityChecker(() -> new IntegrityReport(List.of(), 0, 0, 0, 0, 0));
        CollectionBackupPort backup = mock(CollectionBackupPort.class);
        SearchIndexLifecycle search = mock(SearchIndexLifecycle.class);
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        LibraryHealthService service = new LibraryHealthService(checker, artifacts, backup, search, coordinator, 168);

        try (var ignored = coordinator.acquire(LibraryOperationType.SWITCH)) {
            assertThatThrownBy(service::refresh)
                    .isInstanceOf(LibraryOperationConflictException.class);
        }
    }

}
