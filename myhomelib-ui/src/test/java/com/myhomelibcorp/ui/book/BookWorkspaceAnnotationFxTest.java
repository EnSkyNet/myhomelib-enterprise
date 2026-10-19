package com.myhomelibcorp.ui.book;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.usecase.group.AddBookToGroupUseCase;
import com.myhomelibcorp.application.usecase.group.LoadGroupsUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.reader.inspection.DocumentInspection;
import com.myhomelibcorp.ui.details.BookDetailsAnalysisService;
import com.myhomelibcorp.ui.details.model.RichBookDetailsSession;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.presenter.CoverPresenter;
import com.myhomelibcorp.ui.service.*;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BookWorkspaceAnnotationFxTest {
    @BeforeAll
    static void fxStart() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        try { Platform.startup(latch::countDown); }
        catch (IllegalStateException alreadyRunning) { latch.countDown(); }
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        Platform.setImplicitExit(false);
    }

    @Test
    void fillsMissingCatalogAnnotationFromLocalFb2WithoutBlockingBookDetails() throws Exception {
        String id = "33333333-3333-3333-3333-333333333333";
        BookId bookId = BookId.fromString(id);
        BookDto book = BookDto.builder().id(id).title("Пожиратель душ. Том 18")
                .annotation("").local(true).fileName("volume-18.fb2").build();
        LoadBookByIdUseCase loader = mock(LoadBookByIdUseCase.class);
        when(loader.execute(bookId)).thenReturn(Optional.of(book));
        BookDetailsAnalysisService analysis = mock(BookDetailsAnalysisService.class);
        DocumentInspection inspection = mock(DocumentInspection.class);
        when(inspection.annotation()).thenReturn("Описание из FB2");
        when(analysis.analyze(id)).thenReturn(
                new RichBookDetailsSession(book, inspection, List.of(), null, null));

        UiBackgroundExecutor executor = new UiBackgroundExecutor(new LibraryOperationCoordinator());
        BookViewModelMapper mapper = mock(BookViewModelMapper.class);
        when(mapper.toViewModel(book)).thenReturn(new BookViewModel());
        ApplicationState state = mock(ApplicationState.class);
        when(state.getCurrentLibraryCollectionId()).thenReturn("flib");
        BookWorkspaceController controller = controller(loader, analysis, mapper, executor, state);
        CountDownLatch descriptionAppeared = new CountDownLatch(1);
        AtomicReference<Label> annotationRef = new AtomicReference<>();
        try {
            onFx(() -> {
                Label annotation = attachLabels(controller);
                annotationRef.set(annotation);
                annotation.textProperty().addListener((obs, oldValue, newValue) -> {
                    if ("Описание из FB2".equals(newValue)) descriptionAppeared.countDown();
                });
                controller.setBookId(bookId);
                return null;
            });
            assertThat(descriptionAppeared.await(8, TimeUnit.SECONDS)).isTrue();
            assertThat(onFx(() -> annotationRef.get().getText())).isEqualTo("Описание из FB2");
            assertThat(onFx(() -> getLabel(controller, "titleLabel").getText()))
                    .isEqualTo("Пожиратель душ. Том 18");
            verify(analysis, times(1)).analyze(id);
        } finally {
            onFx(() -> { controller.dispose(); return null; });
            executor.shutdown();
        }
    }

    @Test
    void discoversAlreadyDownloadedOnlineFb2WhenCatalogueStillClaimsRemote() throws Exception {
        String id = "44444444-4444-4444-4444-444444444444";
        BookId bookId = BookId.fromString(id);
        BookDto book = BookDto.builder().id(id).title("Пожиратель душ. Том 18")
                .annotation("").local(false).fileName("729586.fb2").build();
        LoadBookByIdUseCase loader = mock(LoadBookByIdUseCase.class);
        when(loader.execute(bookId)).thenReturn(Optional.of(book));
        BookDownloadCoordinator downloads = mock(BookDownloadCoordinator.class);
        when(downloads.reconcileLocalClaim(book)).thenAnswer(inv -> {
            book.setLocal(true); // An already existing local file is discovered; never downloaded.
            return true;
        });
        BookDetailsAnalysisService analysis = mock(BookDetailsAnalysisService.class);
        DocumentInspection inspection = mock(DocumentInspection.class);
        when(inspection.annotation()).thenReturn("Описание из ранее загруженного FB2");
        when(analysis.analyze(id)).thenReturn(
                new RichBookDetailsSession(book, inspection, List.of(), null, null));

        UiBackgroundExecutor executor = new UiBackgroundExecutor(new LibraryOperationCoordinator());
        BookViewModelMapper mapper = mock(BookViewModelMapper.class);
        when(mapper.toViewModel(book)).thenReturn(new BookViewModel());
        ApplicationState state = mock(ApplicationState.class);
        when(state.getCurrentLibraryCollectionId()).thenReturn("flib");
        BookWorkspaceController controller = controller(loader, analysis, mapper, executor, state, downloads);
        CountDownLatch displayed = new CountDownLatch(1);
        try {
            onFx(() -> {
                Label annotation = attachLabels(controller);
                annotation.textProperty().addListener((obs, oldValue, value) -> {
                    if ("Описание из ранее загруженного FB2".equals(value)) displayed.countDown();
                });
                controller.setBookId(bookId);
                return null;
            });
            assertThat(displayed.await(8, TimeUnit.SECONDS)).isTrue();
            verify(downloads, times(1)).reconcileLocalClaim(book);
            verify(analysis, times(1)).analyze(id);
            verify(downloads, never()).downloadBatch(any(), any());
        } finally {
            onFx(() -> { controller.dispose(); return null; });
            executor.shutdown();
        }
    }

    @Test
    void genuinelyRemoteBookDoesNotTriggerDocumentInspectionOrNetworkDownload() throws Exception {
        String id = "55555555-5555-5555-5555-555555555555";
        BookId bookId = BookId.fromString(id);
        BookDto book = BookDto.builder().id(id).title("Remote")
                .annotation("").local(false).fileName("729586.fb2").build();
        LoadBookByIdUseCase loader = mock(LoadBookByIdUseCase.class);
        when(loader.execute(bookId)).thenReturn(Optional.of(book));
        BookDownloadCoordinator downloads = mock(BookDownloadCoordinator.class);
        CountDownLatch checkedDisk = new CountDownLatch(1);
        when(downloads.reconcileLocalClaim(book)).thenAnswer(inv -> {
            checkedDisk.countDown();
            return false;
        });
        BookDetailsAnalysisService analysis = mock(BookDetailsAnalysisService.class);
        UiBackgroundExecutor executor = new UiBackgroundExecutor(new LibraryOperationCoordinator());
        BookViewModelMapper mapper = mock(BookViewModelMapper.class);
        when(mapper.toViewModel(book)).thenReturn(new BookViewModel());
        ApplicationState state = mock(ApplicationState.class);
        when(state.getCurrentLibraryCollectionId()).thenReturn("flib");
        BookWorkspaceController controller = controller(loader, analysis, mapper, executor, state, downloads);
        try {
            onFx(() -> { attachLabels(controller); controller.setBookId(bookId); return null; });
            assertThat(checkedDisk.await(8, TimeUnit.SECONDS)).isTrue();
            assertThat(onFx(() -> getLabel(controller, "annotationLabel").getText())).isBlank();
            verifyNoInteractions(analysis);
            verify(downloads, never()).downloadBatch(any(), any());
        } finally {
            onFx(() -> { controller.dispose(); return null; });
            executor.shutdown();
        }
    }

    @Test
    void catalogAnnotationTakesPriorityAndRemoteBooksDoNotParse() {
        assertThat(BookWorkspaceController.preferredAnnotation("З каталогу", "З FB2")).isEqualTo("З каталогу");
        assertThat(BookWorkspaceController.preferredAnnotation(" ", "  З FB2  ")).isEqualTo("З FB2");
        assertThat(BookWorkspaceController.preferredAnnotation(null, null)).isEmpty();
    }

    private static BookWorkspaceController controller(
            LoadBookByIdUseCase loader, BookDetailsAnalysisService analysis,
            BookViewModelMapper mapper, UiBackgroundExecutor executor, ApplicationState state) {
        return controller(loader, analysis, mapper, executor, state, mock(BookDownloadCoordinator.class));
    }

    private static BookWorkspaceController controller(
            LoadBookByIdUseCase loader, BookDetailsAnalysisService analysis,
            BookViewModelMapper mapper, UiBackgroundExecutor executor, ApplicationState state,
            BookDownloadCoordinator downloads) {
        return new BookWorkspaceController(loader, mock(LoadGroupsUseCase.class),
                mock(AddBookToGroupUseCase.class), mock(CoverPresenter.class),
                mock(NavigationService.class), mapper, mock(DialogService.class),
                downloads, mock(BookSaver.class),
                mock(ClassicLibraryActionsService.class), executor, state, analysis);
    }

    private static Label attachLabels(BookWorkspaceController controller) {
        for (String field : List.of("titleLabel", "authorLabel", "seriesLabel", "genresLabel",
                "languageLabel", "yearLabel", "publisherLabel", "isbnLabel",
                "formatLabel", "sizeLabel", "ratingLabel", "annotationLabel",
                "progressLabel", "loadStateLabel")) {
            try { set(controller, field, new Label()); }
            catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        try { set(controller, "readingProgress", new ProgressBar()); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        return getLabel(controller, "annotationLabel");
    }

    private static Label getLabel(BookWorkspaceController controller, String name) {
        try {
            Field f = BookWorkspaceController.class.getDeclaredField(name);
            f.setAccessible(true);
            return (Label) f.get(controller);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void set(BookWorkspaceController controller, String name, Object value)
            throws ReflectiveOperationException {
        Field f = BookWorkspaceController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) return action.call();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try { result.set(action.call()); }
            catch (Throwable error) { failure.set(error); }
            finally { completed.countDown(); }
        });
        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }
}
