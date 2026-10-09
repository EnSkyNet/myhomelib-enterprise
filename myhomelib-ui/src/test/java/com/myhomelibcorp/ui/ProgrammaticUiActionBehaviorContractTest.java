package com.myhomelibcorp.ui;

import com.myhomelibcorp.application.action.BookActionCommand;
import com.myhomelibcorp.application.action.BookActionProfile;
import com.myhomelibcorp.application.action.BookActionProfileService;
import com.myhomelibcorp.application.usecase.book.RunBookActionUseCase;
import com.myhomelibcorp.ui.action.BookActionProfilesDialog;
import com.myhomelibcorp.ui.action.BookActionUiService;
import com.myhomelibcorp.ui.annotation.AnnotationEditorDialog;
import com.myhomelibcorp.ui.navigation.AlphabetToolbarController;
import com.myhomelibcorp.ui.readinglist.ReadingListBookActionUiService;
import com.myhomelibcorp.ui.service.BookDownloadProgressDialog;
import com.myhomelibcorp.ui.service.CatalogUpdateProgressDialog;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ProgrammaticUiActionBehaviorContractTest {
    @BeforeAll
    static void startFx() throws Exception { UiActionTestSupport.startFx(); }

    @Test
    void BookActionUiService_itemExecutesSelectedProfileAndUpdatesVisibleStatus() throws Exception {
        BookActionProfileService profiles = mock(BookActionProfileService.class);
        RunBookActionUseCase useCase = mock(RunBookActionUseCase.class);
        DialogService dialogs = mock(DialogService.class);
        ApplicationState state = new ApplicationState();
        BookActionProfile profile = new BookActionProfile("p1", "Export", true,
                List.of(new BookActionCommand("tool", "%FILE%", "%DIR%", false)));
        when(profiles.loadProfiles()).thenReturn(List.of(profile));
        CompletableFuture<com.myhomelibcorp.application.action.BookActionRunResult> pending = new CompletableFuture<>();
        when(useCase.execute(any(), anyString())).thenReturn(pending);
        ReadingListBookActionUiService readingLists = mock(ReadingListBookActionUiService.class);
        when(readingLists.createMenu(any())).thenAnswer(ignored -> new Menu("Reading lists"));
        BookActionUiService service = new BookActionUiService(profiles, useCase, dialogs, state, readingLists);
        BookViewModel book = new BookViewModel(); book.setId("00000000-0000-0000-0000-000000000001"); book.setTitle("Book");

        ContextMenu menu = UiActionTestSupport.fx(() -> service.createContextMenu(book));
        MenuItem item = ((Menu) menu.getItems().getFirst()).getItems().getFirst();
        UiActionTestSupport.fx(() -> { item.fire(); return null; });

        verify(useCase).execute(com.myhomelibcorp.domain.model.valueobject.BookId.fromString("00000000-0000-0000-0000-000000000001"), "p1");
        assertThat(state.getStatusBar().statusTextProperty().get()).contains("Export");
    }

    @Test
    void AnnotationEditorDialog_tagSuggestionsAppendWithoutDuplicatingExistingTag() throws Exception {
        Method appendTag = AnnotationEditorDialog.class.getDeclaredMethod("appendTag", String.class, String.class);
        appendTag.setAccessible(true);
        String appended = (String) appendTag.invoke(null, "favorite, sci-fi", " favorite ");
        String newTag = (String) appendTag.invoke(null, appended, "classic");
        assertThat(appended).isEqualTo("favorite, sci-fi");
        assertThat(newTag).isEqualTo("favorite, sci-fi, classic");
    }

    @Test
    void AlphabetToolbarController_btnSelectsLetterAndInvokesCallback() throws Exception {
        AlphabetToolbarController controller = new AlphabetToolbarController();
        HBox container = UiActionTestSupport.fx(HBox::new);
        setField(controller, "alphabetToolbarContainer", container);
        AtomicReference<Character> selected = new AtomicReference<>();
        controller.setOnLetterSelected(selected::set);
        UiActionTestSupport.fx(() -> { controller.initialize(); return null; });
        Button btn = UiActionTestSupport.fx(() -> descendants(container).stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b -> "A".equals(b.getText())).findFirst().orElseThrow());

        UiActionTestSupport.fx(() -> { btn.fire(); return null; });

        assertThat(selected.get()).isEqualTo('A');
        assertThat(controller.getSelectedLetter()).isEqualTo('A');
        assertThat(UiActionTestSupport.fx(() -> btn.getStyleClass().contains("alphabet-selected"))).isTrue();
    }

    @Test
    void BookDownloadProgressDialog_closeButtonClosesCompletedWindow() throws Exception {
        BookDownloadProgressDialog dialog = UiActionTestSupport.fx(() -> new BookDownloadProgressDialog(null, 1));
        UiActionTestSupport.fx(() -> { dialog.show(); dialog.complete(1, 1, 0, 0); return null; });
        Stage stage = (Stage) getField(dialog, "stage");
        Button closeButton = (Button) getField(dialog, "closeButton");
        assertThat(UiActionTestSupport.fx(stage::isShowing)).isTrue();
        assertThat(UiActionTestSupport.fx(closeButton::isDisabled)).isFalse();

        UiActionTestSupport.fx(() -> { closeButton.fire(); return null; });

        assertThat(UiActionTestSupport.fx(stage::isShowing)).isFalse();
    }

    @Test
    void CatalogUpdateProgressDialog_cancelButtonRequestsCancellationOnceAndDisablesItself() throws Exception {
        CatalogUpdateProgressDialog dialog = UiActionTestSupport.fx(() -> new CatalogUpdateProgressDialog(null));
        AtomicInteger cancellations = new AtomicInteger();
        dialog.setOnCancel(cancellations::incrementAndGet);
        Button cancelButton = (Button) getField(dialog, "cancelButton");

        UiActionTestSupport.fx(() -> { cancelButton.fire(); cancelButton.fire(); return null; });

        assertThat(cancellations.get()).isEqualTo(1);
        assertThat(UiActionTestSupport.fx(cancelButton::isDisabled)).isTrue();
    }

    @Test
    void BookActionProfilesDialog_addProfileRemoveProfileAndCommandOrderingActionsMutateVisibleLists() throws Exception {
        BookActionProfileService profileService = mock(BookActionProfileService.class);
        var execution = mock(com.myhomelibcorp.application.action.BookActionExecutionService.class);
        DialogService dialogs = mock(DialogService.class);
        BookActionCommand first = new BookActionCommand("first", "", "%DIR%", false);
        BookActionCommand second = new BookActionCommand("second", "", "%DIR%", false);
        BookActionProfile initial = new BookActionProfile("p1", "Initial", true, List.of(first, second));
        BookActionProfile created = new BookActionProfile("p2", "Created", true, List.of());
        when(profileService.loadProfiles()).thenReturn(List.of(initial));
        when(profileService.newProfile(anyString())).thenReturn(created);
        when(execution.preview(any(), anyMap())).thenReturn(new com.myhomelibcorp.application.action.BookActionPreview(
                List.of(new com.myhomelibcorp.application.action.BookActionPreview.PreviewCommand(
                        List.of("tool", "sample.fb2"), "/tmp", false))));
        BookActionProfilesDialog service = new BookActionProfilesDialog(profileService, execution, dialogs, mock(com.myhomelibcorp.ui.service.FileChooserService.class));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        Platform.runLater(() -> service.show(null));
        Platform.runLater(() -> {
            try {
                DialogPane outer = latestDialogPane();
                SplitPane split = (SplitPane) outer.getContent();
                VBox left = (VBox) split.getItems().get(0);
                VBox right = (VBox) split.getItems().get(1);
                @SuppressWarnings("unchecked") ListView<Object> profiles = (ListView<Object>) left.getChildren().get(1);
                HBox profileButtons = (HBox) left.getChildren().get(2);
                Button addProfile = (Button) profileButtons.getChildren().get(0);
                Button removeProfile = (Button) profileButtons.getChildren().get(1);
                @SuppressWarnings("unchecked") ListView<BookActionCommand> commands = (ListView<BookActionCommand>) right.getChildren().get(5);
                HBox commandButtons = (HBox) right.getChildren().get(6);
                Button removeCommand = (Button) commandButtons.getChildren().get(2);
                Button upCommand = (Button) commandButtons.getChildren().get(3);
                Button downCommand = (Button) commandButtons.getChildren().get(4);
                Button preview = (Button) commandButtons.getChildren().get(5);

                addProfile.fire();
                assertThat(profiles.getItems()).hasSize(2);
                verify(profileService).newProfile(anyString());
                removeProfile.fire();
                assertThat(profiles.getItems()).hasSize(1);

                profiles.getSelectionModel().selectFirst();
                Platform.runLater(() -> {
                    DialogPane previewPane = latestDialogPane();
                    assertThat(((TextArea) previewPane.getContent()).getText()).contains("tool");
                    ((Button) previewPane.lookupButton(ButtonType.OK)).fire();
                });
                preview.fire();
                verify(execution).preview(any(BookActionProfile.class), anyMap());

                commands.getSelectionModel().select(1);
                upCommand.fire();
                assertThat(commands.getItems()).extracting(BookActionCommand::executable).containsExactly("second", "first");
                downCommand.fire();
                assertThat(commands.getItems()).extracting(BookActionCommand::executable).containsExactly("first", "second");
                commands.getSelectionModel().select(1);
                removeCommand.fire();
                assertThat(commands.getItems()).extracting(BookActionCommand::executable).containsExactly("first");
                ((Button) outer.lookupButton(ButtonType.CANCEL)).fire();
            } catch (Throwable t) { error.set(t); closeAllWindows(); }
            finally { done.countDown(); }
        });

        assertThat(done.await(8, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) throw new AssertionError(error.get());
    }


    @Test
    void BookActionProfilesDialog_chooseExeAndChooseDirActionsPopulateCommandFieldsFromChooserBoundary() throws Exception {
        BookActionProfileService profileService = mock(BookActionProfileService.class);
        var execution = mock(com.myhomelibcorp.application.action.BookActionExecutionService.class);
        DialogService dialogs = mock(DialogService.class);
        com.myhomelibcorp.ui.service.FileChooserService chooser = mock(com.myhomelibcorp.ui.service.FileChooserService.class);
        java.io.File executableFile = new java.io.File("/tmp/mhl-tool");
        java.io.File workingDir = new java.io.File("/tmp/mhl-work");
        when(chooser.chooseFile(isNull(), anyString(), anyList())).thenReturn(executableFile);
        when(chooser.chooseDirectory(isNull(), anyString())).thenReturn(workingDir);
        BookActionProfilesDialog service = new BookActionProfilesDialog(profileService, execution, dialogs, chooser);
        Method editCommand = BookActionProfilesDialog.class.getDeclaredMethod("editCommand", Window.class, BookActionCommand.class);
        editCommand.setAccessible(true);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Optional<BookActionCommand>> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                @SuppressWarnings("unchecked") Optional<BookActionCommand> value =
                        (Optional<BookActionCommand>) editCommand.invoke(service, null, null);
                result.set(value);
            } catch (Throwable t) { error.set(t.getCause() == null ? t : t.getCause()); }
            finally { done.countDown(); }
        });
        Platform.runLater(() -> {
            try {
                DialogPane pane = latestDialogPane();
                GridPane grid = (GridPane) pane.getContent();
                List<Button> chooserButtons = grid.getChildren().stream()
                        .filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(button -> "...".equals(button.getText())).toList();
                assertThat(chooserButtons).hasSize(2);
                Button chooseExe = chooserButtons.get(0);
                Button chooseDir = chooserButtons.get(1);
                chooseExe.fire();
                chooseDir.fire();
                ((Button) pane.lookupButton(ButtonType.OK)).fire();
            } catch (Throwable t) { error.set(t); closeAllWindows(); }
        });

        assertThat(done.await(8, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) throw new AssertionError(error.get());
        assertThat(result.get()).hasValueSatisfying(command -> {
            assertThat(command.executable()).isEqualTo(executableFile.getAbsolutePath());
            assertThat(command.workingDirectory()).isEqualTo(workingDir.getAbsolutePath());
        });
        verify(chooser).chooseFile(isNull(), anyString(), anyList());
        verify(chooser).chooseDirectory(isNull(), anyString());
    }

    @Test
    void BookActionProfilesDialog_addCommandAndEditCommandReturnValidatedCommandValues() throws Exception {
        BookActionProfileService profileService = mock(BookActionProfileService.class);
        var execution = mock(com.myhomelibcorp.application.action.BookActionExecutionService.class);
        DialogService dialogs = mock(DialogService.class);
        BookActionProfilesDialog service = new BookActionProfilesDialog(profileService, execution, dialogs, mock(com.myhomelibcorp.ui.service.FileChooserService.class));
        Method editCommand = BookActionProfilesDialog.class.getDeclaredMethod("editCommand", Window.class, BookActionCommand.class);
        editCommand.setAccessible(true);

        Optional<BookActionCommand> added = invokeCommandDialog(service, editCommand, null, "tool-one", "--one");
        Optional<BookActionCommand> edited = invokeCommandDialog(service, editCommand,
                new BookActionCommand("old", "", "%DIR%", false), "tool-two", "--two");

        assertThat(added).hasValueSatisfying(c -> { assertThat(c.executable()).isEqualTo("tool-one"); assertThat(c.arguments()).isEqualTo("--one"); });
        assertThat(edited).hasValueSatisfying(c -> { assertThat(c.executable()).isEqualTo("tool-two"); assertThat(c.arguments()).isEqualTo("--two"); });
    }

    @SuppressWarnings("unchecked")
    private static Optional<BookActionCommand> invokeCommandDialog(BookActionProfilesDialog service, Method method,
                                                                    BookActionCommand current, String executable,
                                                                    String arguments) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Optional<BookActionCommand>> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try { result.set((Optional<BookActionCommand>) method.invoke(service, null, current)); }
            catch (Throwable t) { error.set(t.getCause() == null ? t : t.getCause()); }
            finally { done.countDown(); }
        });
        Platform.runLater(() -> {
            try {
                DialogPane pane = latestDialogPane();
                GridPane grid = (GridPane) pane.getContent();
                List<TextField> fields = grid.getChildren().stream().filter(TextField.class::isInstance).map(TextField.class::cast).toList();
                fields.get(0).setText(executable); fields.get(1).setText(arguments);
                ((Button) pane.lookupButton(ButtonType.OK)).fire();
            } catch (Throwable t) { error.set(t); closeAllWindows(); }
        });
        assertThat(done.await(8, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) throw new AssertionError(error.get());
        return result.get();
    }

    private static DialogPane latestDialogPane() {
        List<Window> windows = Window.getWindows().stream().filter(Window::isShowing).toList();
        for (int i = windows.size() - 1; i >= 0; i--) {
            Node root = windows.get(i).getScene() == null ? null : windows.get(i).getScene().getRoot();
            if (root instanceof DialogPane pane) return pane;
        }
        throw new AssertionError("No visible DialogPane");
    }

    private static void closeAllWindows() {
        for (Window w : List.copyOf(Window.getWindows())) if (w instanceof Stage s) s.close();
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static List<Node> descendants(Parent parent) {
        java.util.ArrayList<Node> out = new java.util.ArrayList<>();
        for (Node child : parent.getChildrenUnmodifiable()) { out.add(child); if (child instanceof Parent p) out.addAll(descendants(p)); }
        return out;
    }
}
