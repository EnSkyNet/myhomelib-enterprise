package com.myhomelibcorp.ui.organizer;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.organizer.apply.GetLibraryOrganizationStatusUseCase;
import com.myhomelibcorp.organizer.apply.ApplyResult;
import com.myhomelibcorp.organizer.apply.UndoLibraryOrganizationUseCase;
import com.myhomelibcorp.organizer.apply.LibraryOrganizationStatus;
import com.myhomelibcorp.organizer.apply.RecoveryDisposition;
import com.myhomelibcorp.organizer.journal.OrganizationJournalOperation;
import com.myhomelibcorp.organizer.plan.OrganizationPlan;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FileChooserService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.operation.OperationKind;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LibraryOrganizerUiServiceActionBehaviorContractTest {
    @BeforeAll
    static void startFx() throws Exception { UiActionTestSupport.startFx(); }

    @TempDir Path tempDir;

    @Test
    void currentCollectionSource_openDestinationButton_and_closeButtonHaveObservableBehavior() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(LibraryOrganizerUiService.class);
        ApplicationState appState = fixture.dependency(ApplicationState.class);
        Collection collection = mock(Collection.class);
        when(collection.getRootFolder()).thenReturn(tempDir);
        when(appState.getCurrentLibraryCollection()).thenReturn(collection);
        when(fixture.dependency(GetLibraryOrganizationStatusUseCase.class).incompleteOverview()).thenReturn(List.of());

        Object wizard = newWizard(fixture.instance());
        RadioButton currentCollectionSource = field(wizard, "currentCollectionSource", RadioButton.class);
        TextField sourceField = field(wizard, "sourceField", TextField.class);
        TextField destinationField = field(wizard, "destinationField", TextField.class);
        UiActionTestSupport.fx(() -> { sourceField.clear(); destinationField.clear(); currentCollectionSource.getOnAction().handle(new javafx.event.ActionEvent()); return null; });
        String sourceText = UiActionTestSupport.fx(sourceField::getText);
        String destinationText = UiActionTestSupport.fx(destinationField::getText);
        assertThat(sourceText).isEqualTo(tempDir.toAbsolutePath().normalize().toString());
        assertThat(destinationText).isEqualTo(tempDir.toAbsolutePath().normalize().toString());

        Button nextButton = field(wizard, "nextButton", Button.class);
        UiActionTestSupport.fx(() -> { nextButton.fire(); return null; });
        assertThat(UiActionTestSupport.getField(wizard, "pageIndex")).isEqualTo(1);

        UiActionTestSupport.fx(() -> { destinationField.setText(tempDir.resolve("missing-destination").toString()); return null; });
        Button openDestinationButton = field(wizard, "openDestinationButton", Button.class);
        UiActionTestSupport.fx(() -> { openDestinationButton.fire(); return null; });
        verify(fixture.dependency(DialogService.class)).showWarning(anyString(), anyString());

        Stage stage = field(wizard, "stage", Stage.class);
        Button closeButton = field(wizard, "closeButton", Button.class);
        UiActionTestSupport.fx(() -> { stage.show(); return null; });
        assertThat(UiActionTestSupport.fx(stage::isShowing)).isTrue();
        UiActionTestSupport.fx(() -> { closeButton.fire(); return null; });
        assertThat(UiActionTestSupport.fx(stage::isShowing)).isFalse();
    }


    @Test
    void backButton_externalFolderSource_chooseSource_chooseDestination_selectVisible_clearVisibleHaveObservableEffects() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(LibraryOrganizerUiService.class);
        when(fixture.dependency(GetLibraryOrganizationStatusUseCase.class).incompleteOverview()).thenReturn(List.of());
        FileChooserService chooser = fixture.dependency(FileChooserService.class);
        Path source = Files.createDirectory(tempDir.resolve("source"));
        Path destination = Files.createDirectory(tempDir.resolve("destination"));
        when(chooser.chooseDirectory(any(Stage.class), anyString(), nullable(java.io.File.class)))
                .thenReturn(source.toFile(), source.toFile(), destination.toFile());

        Object wizard = newWizard(fixture.instance());
        Button backButton = field(wizard, "backButton", Button.class);
        Method showPage = wizard.getClass().getDeclaredMethod("showPage", int.class);
        showPage.setAccessible(true);
        UiActionTestSupport.fx(() -> { showPage.invoke(wizard, 2); backButton.fire(); return null; });
        assertThat(UiActionTestSupport.getField(wizard, "pageIndex")).isEqualTo(1);

        RadioButton externalFolderSource = field(wizard, "externalFolderSource", RadioButton.class);
        TextField sourceField = field(wizard, "sourceField", TextField.class);
        TextField destinationField = field(wizard, "destinationField", TextField.class);
        UiActionTestSupport.fx(() -> {
            sourceField.clear(); destinationField.clear(); externalFolderSource.getOnAction().handle(new javafx.event.ActionEvent()); return null;
        });
        String externalSourceText = UiActionTestSupport.fx(() -> sourceField.getText());
        String externalDestinationText = UiActionTestSupport.fx(() -> destinationField.getText());
        assertThat(externalSourceText).isEqualTo(source.toAbsolutePath().normalize().toString());
        assertThat(externalDestinationText)
                .isEqualTo(source.resolve("MyHomeLib-Organized").toAbsolutePath().normalize().toString());

        @SuppressWarnings("unchecked") List<Node> pages = (List<Node>) UiActionTestSupport.getField(wizard, "pages");
        Parent sourcePage = pageRoot(pages.get(0));
        List<Button> sourceButtons = descendants(sourcePage).stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
        assertThat(sourceButtons).hasSizeGreaterThanOrEqualTo(3);
        Button chooseSource = sourceButtons.get(sourceButtons.size() - 3);
        Button chooseDestination = sourceButtons.get(sourceButtons.size() - 2);
        UiActionTestSupport.fx(() -> { chooseSource.fire(); chooseDestination.fire(); return null; });
        String chosenSourceText = UiActionTestSupport.fx(() -> sourceField.getText());
        String chosenDestinationText = UiActionTestSupport.fx(() -> destinationField.getText());
        assertThat(chosenSourceText).isEqualTo(source.toAbsolutePath().normalize().toString());
        assertThat(chosenDestinationText).isEqualTo(destination.toAbsolutePath().normalize().toString());

        Object row = newPreviewRow(wizard);
        @SuppressWarnings("unchecked") javafx.collections.ObservableList<Object> previewRows =
                (javafx.collections.ObservableList<Object>) UiActionTestSupport.getField(wizard, "previewRows");
        UiActionTestSupport.fx(() -> { previewRows.add(row); return null; });
        Parent previewPage = pageRoot(pages.get(3));
        List<Button> previewButtons = descendants(previewPage).stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
        assertThat(previewButtons).hasSizeGreaterThanOrEqualTo(2);
        Button selectVisible = previewButtons.get(0);
        Button clearVisible = previewButtons.get(1);
        UiActionTestSupport.fx(() -> { clearVisible.fire(); return null; });
        assertThat(rowIncluded(row)).isFalse();
        UiActionTestSupport.fx(() -> { selectVisible.fire(); return null; });
        assertThat(rowIncluded(row)).isTrue();
        UiActionTestSupport.fx(() -> { clearVisible.fire(); return null; });
        assertThat(rowIncluded(row)).isFalse();

        verify(chooser, times(3)).chooseDirectory(any(Stage.class), anyString(), nullable(java.io.File.class));
        UiActionTestSupport.fx(() -> { field(wizard, "stage", Stage.class).close(); return null; });
    }

    @Test
    void authorPreviewButtonExplainsMissingCollectionWithoutStartingRepair() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(LibraryOrganizerUiService.class);
        when(fixture.dependency(GetLibraryOrganizationStatusUseCase.class).incompleteOverview())
                .thenReturn(List.of());
        Object wizard = newWizard(fixture.instance());
        Button authorPreviewButton = field(wizard, "authorPreviewButton", Button.class);
        UiActionTestSupport.fx(() -> { authorPreviewButton.fire(); return null; });
        verify(fixture.dependency(DialogService.class)).showWarning(anyString(), anyString());
        verify(fixture.dependency(DialogService.class), never()).showTextInput(anyString(), anyString(), anyString(), anyString());
        UiActionTestSupport.fx(() -> { field(wizard, "stage", Stage.class).close(); return null; });
    }

    @Test
    void authorPreviewRejectsInvalidDatabasePathBeforeSubmittingWork() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(LibraryOrganizerUiService.class);
        ApplicationState state = fixture.dependency(ApplicationState.class);
        Collection collection = mock(Collection.class);
        when(collection.getRootFolder()).thenReturn(tempDir);
        when(collection.getDbFile()).thenReturn("invalid\u0000path");
        when(state.getCurrentLibraryCollection()).thenReturn(collection);
        when(fixture.dependency(GetLibraryOrganizationStatusUseCase.class).incompleteOverview())
                .thenReturn(List.of());

        when(fixture.dependency(DialogService.class).showTextInput(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of("Example Author"));
        Object wizard = newWizard(fixture.instance());
        RadioButton current = field(wizard, "currentCollectionSource", RadioButton.class);
        Button button = field(wizard, "authorPreviewButton", Button.class);
        UiActionTestSupport.fx(() -> { current.setSelected(true); button.fire(); return null; });
        verify(fixture.dependency(DialogService.class)).showError(anyString(), anyString());
        verify(fixture.dependency(DialogService.class)).showTextInput(anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(fixture.dependency(com.myhomelibcorp.organizer.analysis.AuthorFolderPreviewPort.class));
        UiActionTestSupport.fx(() -> { field(wizard, "stage", Stage.class).close(); return null; });
    }

    @Test
    void authorBatchExportRejectsOutputInsideLibraryBeforeSchedulingWrite() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(LibraryOrganizerUiService.class);
        ApplicationState state = fixture.dependency(ApplicationState.class);
        Collection collection = mock(Collection.class);
        when(collection.getRootFolder()).thenReturn(tempDir);
        when(collection.getDbFile()).thenReturn(tempDir.resolve("Flib.db").toString());
        when(state.getCurrentLibraryCollection()).thenReturn(collection);
        when(fixture.dependency(GetLibraryOrganizationStatusUseCase.class).incompleteOverview())
                .thenReturn(List.of());
        Path unsafeOutput = tempDir.resolve("author-audit.csv");
        when(fixture.dependency(FileChooserService.class).chooseFileToSave(
                any(Window.class), anyString(), anyString(), anyList()))
                .thenReturn(unsafeOutput.toFile());

        Object wizard = newWizard(fixture.instance());
        RadioButton current = field(wizard, "currentCollectionSource", RadioButton.class);
        Button button = field(wizard, "authorBatchButton", Button.class);
        UiActionTestSupport.fx(() -> { current.setSelected(true); button.fire(); return null; });
        verify(fixture.dependency(DialogService.class)).showWarning(anyString(), anyString());
        assertThat(Files.exists(unsafeOutput)).isFalse();
        verifyNoInteractions(fixture.dependency(com.myhomelibcorp.organizer.analysis.AuthorFolderPreviewPort.class));
        UiActionTestSupport.fx(() -> { field(wizard, "stage", Stage.class).close(); return null; });
    }

    @Test
    void authorSelectionButtonDoesNotStartOfflineRepairWhileNoCollectionIsSelected() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(LibraryOrganizerUiService.class);
        when(fixture.dependency(GetLibraryOrganizationStatusUseCase.class).incompleteOverview())
                .thenReturn(List.of());
        Object wizard = newWizard(fixture.instance());
        Button authorSelectionButton = field(wizard, "authorSelectionButton", Button.class);
        UiActionTestSupport.fx(() -> { authorSelectionButton.fire(); return null; });
        verify(fixture.dependency(DialogService.class)).showWarning(anyString(), anyString());
        verify(fixture.dependency(FileChooserService.class), never()).chooseFileToSave(
                any(Window.class), anyString(), anyString(), anyList());
        UiActionTestSupport.fx(() -> { field(wizard, "stage", Stage.class).close(); return null; });
    }

    @Test
    void authorBatchButtonRejectsMissingCollectionBeforeFileDialog() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(LibraryOrganizerUiService.class);
        when(fixture.dependency(GetLibraryOrganizationStatusUseCase.class).incompleteOverview()).thenReturn(List.of());
        Object wizard = newWizard(fixture.instance());
        Button authorBatchButton = field(wizard, "authorBatchButton", Button.class);
        UiActionTestSupport.fx(() -> { authorBatchButton.fire(); return null; });
        verify(fixture.dependency(DialogService.class)).showWarning(anyString(), anyString());
        verify(fixture.dependency(FileChooserService.class), never()).chooseFileToSave(
                any(Window.class), anyString(), anyString(), anyList());
        UiActionTestSupport.fx(() -> { field(wizard, "stage", Stage.class).close(); return null; });
    }

    private static Parent pageRoot(Node node) {
        if (node instanceof ScrollPane scroll && scroll.getContent() instanceof Parent parent) return parent;
        return (Parent) node;
    }

    private static List<Node> descendants(Parent parent) {
        ArrayList<Node> out = new ArrayList<>();
        for (Node child : parent.getChildrenUnmodifiable()) {
            out.add(child);
            if (child instanceof Parent nested) out.addAll(descendants(nested));
        }
        return out;
    }

    private static Object newPreviewRow(Object wizard) throws Exception {
        Class<?> rowType = java.util.Arrays.stream(wizard.getClass().getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("PreviewRow")).findFirst().orElseThrow();
        Constructor<?> ctor = rowType.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Class<?> planItemType = ctor.getParameterTypes()[1];
        Object planItem = mock(planItemType);
        // Stub the two properties used by safeDefaultSelection().
        doReturn(false).when((com.myhomelibcorp.organizer.plan.OrganizationPlanItem) planItem).blocking();
        doReturn(com.myhomelibcorp.organizer.plan.OrganizationAction.MOVE)
                .when((com.myhomelibcorp.organizer.plan.OrganizationPlanItem) planItem).action();
        return ctor.newInstance(wizard, planItem, null);
    }

    private static boolean rowIncluded(Object row) throws Exception {
        Method method = row.getClass().getDeclaredMethod("included");
        method.setAccessible(true);
        return (boolean) method.invoke(row);
    }

    @Test
    void analysisIsPublishedToOperationCenterBeforeBackgroundScanningStarts() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(LibraryOrganizerUiService.class);
        when(fixture.dependency(GetLibraryOrganizationStatusUseCase.class).incompleteOverview()).thenReturn(List.of());
        UiBackgroundExecutor background = fixture.dependency(UiBackgroundExecutor.class);
        when(background.submit(any(java.util.concurrent.Callable.class)))
                .thenReturn(new java.util.concurrent.CompletableFuture<>());
        OperationCenterService operations = fixture.dependency(OperationCenterService.class);
        when(operations.start(anyString(), nullable(String.class), eq(OperationKind.LIBRARY_ORGANIZATION), any(), eq(true), any()))
                .thenReturn("organizer-analysis-1");

        Path source = Files.createDirectory(tempDir.resolve("analysis-source"));
        Path destination = Files.createDirectory(tempDir.resolve("analysis-destination"));
        Object wizard = newWizard(fixture.instance());
        TextField sourceField = field(wizard, "sourceField", TextField.class);
        TextField destinationField = field(wizard, "destinationField", TextField.class);
        UiActionTestSupport.fx(() -> {
            sourceField.setText(source.toString());
            destinationField.setText(destination.toString());
            return null;
        });

        UiActionTestSupport.invoke(wizard, "startAnalysis");

        verify(operations).start(anyString(), nullable(String.class), eq(OperationKind.LIBRARY_ORGANIZATION), any(), eq(true), any());
        verify(background).submit(any(java.util.concurrent.Callable.class));
        UiActionTestSupport.fx(() -> { field(wizard, "stage", Stage.class).close(); return null; });
    }

    @Test
    void recoveryButton_and_exportReportButtonDelegateAndPublishReport() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(LibraryOrganizerUiService.class);
        OrganizationJournalOperation operation = mock(OrganizationJournalOperation.class);
        LibraryOrganizationStatus status = new LibraryOrganizationStatus(
                operation, List.of(), false, false, RecoveryDisposition.NOTHING_TO_ROLLBACK, 0, 0, 0, false);
        when(fixture.dependency(GetLibraryOrganizationStatusUseCase.class).incompleteOverview()).thenReturn(List.of(status));
        when(fixture.dependency(GetLibraryOrganizationStatusUseCase.class).incomplete()).thenReturn(List.of(status));
        DialogService dialogs = fixture.dependency(DialogService.class);
        when(dialogs.showChoiceDialog(anyList(), any(), anyString(), nullable(String.class), anyString(), any()))
                .thenReturn(Optional.empty());

        Object wizard = newWizard(fixture.instance());
        Button recoveryButton = field(wizard, "recoveryButton", Button.class);
        UiActionTestSupport.fx(() -> { recoveryButton.fire(); return null; });
        verify(dialogs).showChoiceDialog(anyList(), eq(status), anyString(), nullable(String.class), anyString(), any());

        Path report = tempDir.resolve("organizer-report.csv");
        FileChooserService chooser = fixture.dependency(FileChooserService.class);
        when(chooser.chooseFileToSave(any(Stage.class), anyString(), eq("myhomelib-organizer-report.csv"), anyList()))
                .thenReturn(report.toFile());
        UiActionTestSupport.setField(wizard, "plan", mock(OrganizationPlan.class));
        Button exportReportButton = field(wizard, "exportReportButton", Button.class);
        UiActionTestSupport.fx(() -> { exportReportButton.fire(); return null; });

        assertThat(report).exists();
        assertThat(Files.readString(report)).startsWith("included;action;source_impact;reason;");
        verify(chooser).chooseFileToSave(any(Stage.class), anyString(), eq("myhomelib-organizer-report.csv"), anyList());
        UiActionTestSupport.fx(() -> { field(wizard, "stage", Stage.class).close(); return null; });
    }

    @Test
    void undoButtonRollsBackAppliedOrganizerOperation() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(LibraryOrganizerUiService.class);
        when(fixture.dependency(GetLibraryOrganizationStatusUseCase.class).incompleteOverview()).thenReturn(List.of());
        when(fixture.dependency(DialogService.class).showConfirmation(anyString(), nullable(String.class), anyString())).thenReturn(true);
        UiBackgroundExecutor background = fixture.dependency(UiBackgroundExecutor.class);
        when(background.submit(any(java.util.concurrent.Callable.class))).thenAnswer(inv -> {
            java.util.concurrent.Callable<?> task = inv.getArgument(0);
            try { return java.util.concurrent.CompletableFuture.completedFuture(task.call()); }
            catch (Exception e) { return java.util.concurrent.CompletableFuture.failedFuture(e); }
        });
        java.util.UUID operationId = java.util.UUID.randomUUID();
        UndoLibraryOrganizationUseCase undoUseCase = fixture.dependency(UndoLibraryOrganizationUseCase.class);
        when(undoUseCase.undo(operationId, "")).thenReturn(3);

        Object wizard = newWizard(fixture.instance());
        UiActionTestSupport.setField(wizard, "applyResult", new ApplyResult(
                operationId, 1, 0, 1, 0, 0, 0, false, 0, 0, 0, 0, List.of()));
        OrganizationPlan appliedPlan = mock(OrganizationPlan.class);
        when(appliedPlan.expectedCollectionId()).thenReturn("");
        UiActionTestSupport.setField(wizard, "appliedPlan", appliedPlan);
        Button undoButton = field(wizard, "undoButton", Button.class);
        javafx.scene.control.Label resultSummary = field(wizard, "resultSummary", javafx.scene.control.Label.class);
        UiActionTestSupport.fx(() -> { undoButton.setDisable(false); undoButton.fire(); return null; });

        verify(undoUseCase).undo(operationId, "");
        assertThat(UiActionTestSupport.fx(resultSummary::getText)).contains("3");
        assertThat(UiActionTestSupport.fx(undoButton::isDisabled)).isTrue();
        UiActionTestSupport.fx(() -> { field(wizard, "stage", Stage.class).close(); return null; });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"success", "cancelled", "partial", "failed", "cancel-exception"})
    void terminalApplyLeavesProgressPageAndShowsOperationId(String outcome) throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(LibraryOrganizerUiService.class);
        when(fixture.dependency(GetLibraryOrganizationStatusUseCase.class).incompleteOverview()).thenReturn(List.of());
        when(fixture.dependency(DialogService.class).showConfirmation(anyString(), nullable(String.class), anyString())).thenReturn(true);
        java.util.concurrent.CompletableFuture<ApplyResult> future = new java.util.concurrent.CompletableFuture<>();
        when(fixture.dependency(UiBackgroundExecutor.class).submit(any(java.util.concurrent.Callable.class))).thenReturn((java.util.concurrent.CompletableFuture) future);
        Object wizard = newWizard(fixture.instance());
        Object row = newPreviewRow(wizard);
        Method planItemMethod = row.getClass().getDeclaredMethod("planItem");
        planItemMethod.setAccessible(true);
        var item = (com.myhomelibcorp.organizer.plan.OrganizationPlanItem) planItemMethod.invoke(row);
        var plan = mock(OrganizationPlan.class);
        var id = java.util.UUID.randomUUID();
        when(plan.operationId()).thenReturn(id);
        when(plan.withActions(anyList())).thenReturn(plan);
        when(plan.actions()).thenReturn(List.of(item));
        when(plan.executable()).thenReturn(true);
        UiActionTestSupport.setField(wizard, "plan", plan);
        @SuppressWarnings("unchecked") javafx.collections.ObservableList<Object> rows =
                (javafx.collections.ObservableList<Object>) UiActionTestSupport.getField(wizard, "previewRows");
        UiActionTestSupport.fx(() -> { rows.add(row); UiActionTestSupport.invoke(wizard, "startApply"); return null; });
        assertThat(UiActionTestSupport.getField(wizard, "pageIndex")).isEqualTo(4);
        UiActionTestSupport.fx(() -> {
            switch (outcome) {
                case "failed" -> future.completeExceptionally(new java.io.IOException("copy failed"));
                case "cancel-exception" -> future.completeExceptionally(new java.util.concurrent.CancellationException("cancelled"));
                default -> future.complete(new ApplyResult(id, 1, 0, 1, 0, 0,
                        outcome.equals("partial") ? 1 : 0, outcome.equals("cancelled"), 0, 0, 0, 0, List.of()));
            }
            return null;
        });
        UiActionTestSupport.fx(() -> null);
        assertThat(UiActionTestSupport.getField(wizard, "pageIndex")).isEqualTo(5);
        assertThat(UiActionTestSupport.getField(wizard, "busy")).isEqualTo(false);
        var details = field(wizard, "resultDetails", javafx.scene.control.TextArea.class);
        String detailText = UiActionTestSupport.fx(details::getText);
        assertThat(detailText).contains(id.toString()).doesNotContain("ui.organizer.result.");
        String status = outcome.startsWith("cancel") ? "cancelled"
                : outcome.equals("failed") || outcome.equals("partial") ? "failed" : "completed";
        assertThat(UiActionTestSupport.fx(field(wizard, "resultSummary", javafx.scene.control.Label.class)::getText))
                .isEqualTo(com.myhomelibcorp.ui.service.UiText.text("ui.organizer.result." + status));
        assertThat(UiActionTestSupport.fx(field(wizard, "undoButton", Button.class)::isDisabled))
                .isEqualTo(outcome.equals("failed") || outcome.equals("cancel-exception"));
        assertThat(UiActionTestSupport.fx(field(wizard, "nextButton", Button.class)::isDisabled)).isFalse();
        assertThat(UiActionTestSupport.fx(field(wizard, "backButton", Button.class)::isDisabled)).isTrue();
        UiActionTestSupport.fx(() -> { field(wizard, "stage", Stage.class).close(); return null; });
    }

    @Test
    void recoveryScanIsScheduledAndDoesNotReadTheDiskOnFxThread() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(LibraryOrganizerUiService.class);
        UiBackgroundExecutor background = fixture.dependency(UiBackgroundExecutor.class);
        var pending = new java.util.concurrent.CompletableFuture<List<LibraryOrganizationStatus>>();
        when(background.submitCollectionScopedOperation(
                eq(com.myhomelibcorp.application.operation.LibraryOperationType.QUERY),
                any(java.util.concurrent.Callable.class))).thenReturn(pending);
        GetLibraryOrganizationStatusUseCase recovery = fixture.dependency(GetLibraryOrganizationStatusUseCase.class);
        Object wizard = rawWizard(fixture.instance());
        verify(background).submitCollectionScopedOperation(
                eq(com.myhomelibcorp.application.operation.LibraryOperationType.QUERY), any(java.util.concurrent.Callable.class));
        verifyNoInteractions(recovery);
        assertThat(UiActionTestSupport.fx(field(wizard, "recoveryButton", Button.class)::isDisabled)).isTrue();
        OrganizationJournalOperation op = mock(OrganizationJournalOperation.class);
        LibraryOrganizationStatus status = new LibraryOrganizationStatus(
                op, List.of(), false, false, RecoveryDisposition.NOTHING_TO_ROLLBACK, 0, 0, 0, false);
        pending.complete(List.of(status));
        assertThat(UiActionTestSupport.fx(field(wizard, "recoveryButton", Button.class)::isVisible)).isTrue();
        UiActionTestSupport.fx(() -> { field(wizard, "stage", Stage.class).close(); return null; });
    }

    private static Object newWizard(LibraryOrganizerUiService service) throws Exception {
        // Recoverability checks are asynchronous: the mock must complete its worker future.
        UiBackgroundExecutor worker = (UiBackgroundExecutor) UiActionTestSupport.getField(service, "background");
        when(worker.submitCollectionScopedOperation(eq(com.myhomelibcorp.application.operation.LibraryOperationType.QUERY),
                any(java.util.concurrent.Callable.class))).thenAnswer(invocation -> {
            java.util.concurrent.Callable<?> callable = invocation.getArgument(1);
            try { return java.util.concurrent.CompletableFuture.completedFuture(callable.call()); }
            catch (Exception exception) { return java.util.concurrent.CompletableFuture.failedFuture(exception); }
        });
        return rawWizard(service);
    }

    private static Object rawWizard(LibraryOrganizerUiService service) throws Exception {
        Class<?> wizardType = java.util.Arrays.stream(LibraryOrganizerUiService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("Wizard"))
                .findFirst().orElseThrow();
        Constructor<?> ctor = wizardType.getDeclaredConstructor(LibraryOrganizerUiService.class, Window.class);
        ctor.setAccessible(true);
        return UiActionTestSupport.fx(() -> ctor.newInstance(service, null));
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        return type.cast(UiActionTestSupport.getField(target, name));
    }
}
