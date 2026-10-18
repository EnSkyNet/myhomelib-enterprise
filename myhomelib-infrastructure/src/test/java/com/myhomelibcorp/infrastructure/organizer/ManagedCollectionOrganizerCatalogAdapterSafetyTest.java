package com.myhomelibcorp.infrastructure.organizer;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.organizer.plan.OrganizationAction;
import com.myhomelibcorp.organizer.plan.OrganizationPlanItem;
import com.myhomelibcorp.organizer.duplicate.DuplicateDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagedCollectionOrganizerCatalogAdapterSafetyTest {
    @TempDir Path root;

    private OrganizationPlanItem item(Path source, Path target, String entry) {
        return new OrganizationPlanItem(UUID.randomUUID(), source, entry, "", target,
                "fb2", "fb2", OrganizationAction.MOVE_AND_RENAME, false, "", DuplicateDecision.none(),
                100, List.of(), List.of());
    }

    @Test
    void trackedBookCannotMoveOutsideCollectionWithoutCatalogUpdate() {
        CollectionManager collections = mock(CollectionManager.class);
        BookQueryRepository books = mock(BookQueryRepository.class);
        when(collections.getCurrentCollection()).thenReturn(new Collection("Library", root));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(collections.getCurrentJdbcTemplate()).thenReturn(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(1);
        Path source = root.resolve("Author/book.fb2");
        when(books.findByStorage(eq(root.toString()), eq("Author"), eq("book.fb2"), eq("")))
                .thenReturn(Optional.of(mock(Book.class)));
        var adapter = new ManagedCollectionOrganizerCatalogAdapter(collections, books,
                mock(CommittedCatalogMutationService.class));

        assertThatThrownBy(() -> adapter.validateBeforeFilesystemCommit(
                item(source, root.getParent().resolve("outside/book.fb2"), "")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("поза її коренем");

    }

    @Test
    void physicalContainerSharedByMultipleBooksCannotBeMovedWithSingleRowUpdate() {
        CollectionManager collections = mock(CollectionManager.class);
        BookQueryRepository books = mock(BookQueryRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(collections.getCurrentCollection()).thenReturn(new Collection("Library", root));
        when(collections.getCurrentJdbcTemplate()).thenReturn(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(2);
        when(books.findByStorage(eq(root.toString()), eq("Author"), eq("shared.zip"), eq("")))
                .thenReturn(Optional.of(mock(Book.class)));
        var adapter = new ManagedCollectionOrganizerCatalogAdapter(collections, books,
                mock(CommittedCatalogMutationService.class));

        assertThatThrownBy(() -> adapter.validateBeforeFilesystemCommit(
                item(root.resolve("Author/shared.zip"), root.resolve("New/shared.zip"), "")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("кількома книгами");
    }

    @Test
    void archivedPhysicalContainerWithNoEmptyEntryMatchIsRejected() {
        CollectionManager collections = mock(CollectionManager.class);
        BookQueryRepository books = mock(BookQueryRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(collections.getCurrentCollection()).thenReturn(new Collection("Library", root));
        when(collections.getCurrentJdbcTemplate()).thenReturn(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(2);
        var adapter = new ManagedCollectionOrganizerCatalogAdapter(collections, books,
                mock(CommittedCatalogMutationService.class));
        assertThatThrownBy(() -> adapter.validateBeforeFilesystemCommit(
                item(root.resolve("Author/archive.zip"), root.resolve("New/archive.zip"), "")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("кількома книгами");
    }

    @Test
    void ordinarySingleBookInsideCollectionRemainsSupported() {
        CollectionManager collections = mock(CollectionManager.class);
        BookQueryRepository books = mock(BookQueryRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(collections.getCurrentCollection()).thenReturn(new Collection("Library", root));
        when(collections.getCurrentJdbcTemplate()).thenReturn(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(1);
        when(books.findByStorage(eq(root.toString()), eq("Author"), eq("book.fb2"), eq("")))
                .thenReturn(Optional.of(mock(Book.class)));
        var adapter = new ManagedCollectionOrganizerCatalogAdapter(collections, books,
                mock(CommittedCatalogMutationService.class));
        assertThatCode(() -> adapter.validateBeforeFilesystemCommit(
                item(root.resolve("Author/book.fb2"), root.resolve("New/book.fb2"), ""))).doesNotThrowAnyException();
    }
    @Test
    void trackedLogicalArchiveEntryCannotMoveOutsideCollection() {
        CollectionManager collections = mock(CollectionManager.class);
        BookQueryRepository books = mock(BookQueryRepository.class);
        when(collections.getCurrentCollection()).thenReturn(new Collection("Library", root));
        when(books.findByStorage(eq(root.toString()), eq("Author"), eq("shared.fb2.zip"), eq("book.fb2")))
                .thenReturn(Optional.of(mock(Book.class)));
        var adapter = new ManagedCollectionOrganizerCatalogAdapter(collections, books,
                mock(CommittedCatalogMutationService.class));
        assertThatThrownBy(() -> adapter.validateBeforeFilesystemCommit(
                item(root.resolve("Author/shared.fb2.zip"), root.getParent().resolve("out/book.epub"), "book.fb2")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("поза її коренем");
    }

    @Test
    void trackedLogicalArchiveEntryMayExtractInsideCollectionWithoutMovingArchive() {
        CollectionManager collections = mock(CollectionManager.class);
        BookQueryRepository books = mock(BookQueryRepository.class);
        when(collections.getCurrentCollection()).thenReturn(new Collection("Library", root));
        var adapter = new ManagedCollectionOrganizerCatalogAdapter(collections, books,
                mock(CommittedCatalogMutationService.class));
        assertThatCode(() -> adapter.validateBeforeFilesystemCommit(
                item(root.resolve("Author/shared.fb2.zip"), root.resolve("New/book.epub"), "book.fb2")))
                .doesNotThrowAnyException();
        verify(collections, never()).getCurrentJdbcTemplate();
    }

}
