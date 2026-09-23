package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.usecase.book.EditBookUseCase;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.metadata.MetadataMergeUiService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClassicLibraryActionsServiceLifecycleTest {

    @Test
    void newBooksReadsUnderQueryBarrier() {
        LibraryOperationCoordinator operations = new LibraryOperationCoordinator();
        BookQueryRepository query = mock(BookQueryRepository.class);
        when(query.findRecentlyAdded(25)).thenAnswer(invocation -> {
            assertThat(operations.activeOperation()).isEqualTo(LibraryOperationType.QUERY);
            return List.of();
        });

        ClassicLibraryActionsService service = service(query, mock(BookSaver.class),
                mock(ApplicationSettingsPort.class), operations, new ApplicationState());

        assertThat(service.newBooks(25)).isEmpty();
        assertThat(operations.activeOperation()).isNull();
    }

    @Test
    void deleteKeepsCollectionStableFromLookupThroughMutation() {
        LibraryOperationCoordinator operations = new LibraryOperationCoordinator();
        BookQueryRepository query = mock(BookQueryRepository.class);
        BookSaver saver = mock(BookSaver.class);
        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        ApplicationState state = new ApplicationState();
        Collection collection = mock(Collection.class);
        when(collection.getId()).thenReturn("collection-a");
        state.setCurrentLibraryCollection(collection);

        BookId id = mock(BookId.class);
        Book book = mock(Book.class);
        when(query.findById(id)).thenAnswer(invocation -> {
            assertThat(operations.activeOperation()).isEqualTo(LibraryOperationType.EDIT);
            return Optional.of(book);
        });
        when(settings.getBoolean(anyString(), anyBoolean())).thenReturn(false);
        doAnswer(invocation -> {
            assertThat(operations.activeOperation()).isEqualTo(LibraryOperationType.EDIT);
            assertThat(invocation.getArgument(1, String.class)).isEqualTo("collection-a");
            return null;
        }).when(saver).deleteBook(id, "collection-a");

        ClassicLibraryActionsService service = service(query, saver, settings, operations, state);

        assertThat(service.deleteBook(id)).isTrue();
        assertThat(operations.activeOperation()).isNull();
    }

    private static ClassicLibraryActionsService service(BookQueryRepository query,
                                                        BookSaver saver,
                                                        ApplicationSettingsPort settings,
                                                        LibraryOperationCoordinator operations,
                                                        ApplicationState state) {
        return new ClassicLibraryActionsService(
                query,
                mock(BookMapper.class),
                saver,
                settings,
                mock(DialogService.class),
                mock(EditBookUseCase.class),
                mock(UiBackgroundExecutor.class),
                mock(MetadataMergeUiService.class),
                operations,
                state);
    }
}
