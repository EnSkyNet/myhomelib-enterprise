package com.myhomelibcorp.ui;

import com.myhomelibcorp.application.bulkedit.BatchMetadataEditRule;
import com.myhomelibcorp.application.extension.LocalMetadataExtractionService;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.usecase.duplicate.ExactDuplicateScanner;
import com.myhomelibcorp.application.usecase.duplicate.MergeBooksUseCase;
import com.myhomelibcorp.application.usecase.duplicate.ReviewBookDuplicatesUseCase;
import com.myhomelibcorp.ui.duplicate.DuplicateReviewPresenter;
import com.myhomelibcorp.ui.duplicate.DuplicateReviewUiService;
import com.myhomelibcorp.ui.integration.IntegrationBackend;
import com.myhomelibcorp.ui.integration.IntegrationCenterDialog;
import com.myhomelibcorp.ui.metadata.BulkMetadataEditUiService;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProgrammaticDialogActionBehaviorContractTest {
    @BeforeAll
    static void startFx() throws Exception { UiActionTestSupport.startFx(); }

    @Test
    void DuplicateReviewUiService_refreshReloadsEmptySelectionStateAnd_startRejectsMissingCollection() throws Exception {
        ApplicationState state = new ApplicationState();
        ReviewBookDuplicatesUseCase fuzzy = mock(ReviewBookDuplicatesUseCase.class);
        ExactDuplicateScanner exact = mock(ExactDuplicateScanner.class);
        MergeBooksUseCase merge = mock(MergeBooksUseCase.class);
        LoadBookByIdUseCase load = mock(LoadBookByIdUseCase.class);
        DuplicateReviewPresenter presenter = mock(DuplicateReviewPresenter.class);
        UiBackgroundExecutor background = mock(UiBackgroundExecutor.class);
        OperationCenterService operationCenter = mock(OperationCenterService.class);
        DialogService dialogs = mock(DialogService.class);
        when(exact.isRunning()).thenReturn(false);
        DuplicateReviewUiService service = new DuplicateReviewUiService(state, fuzzy, exact, merge, load, presenter, background, operationCenter, dialogs);

        Tab fuzzyTab = UiActionTestSupport.fx(() -> (Tab) invoke(service, "fuzzyTab"));
        VBox fuzzyContent = (VBox) fuzzyTab.getContent();
        HBox fuzzyActions = (HBox) fuzzyContent.getChildren().get(6);
        Button refresh = (Button) fuzzyActions.getChildren().get(0);
        Button undo = (Button) fuzzyActions.getChildren().get(2);
        Label sourceTitle = (Label) fuzzyContent.getChildren().get(1);
        UiActionTestSupport.fx(() -> { refresh.fire(); undo.fire(); return null; });
        assertThat(UiActionTestSupport.fx(sourceTitle::getText)).isNotBlank();
        verifyNoInteractions(fuzzy);
        verifyNoInteractions(merge);

        Tab exactTab = UiActionTestSupport.fx(() -> (Tab) invoke(service, "exactTab"));
        VBox exactContent = (VBox) exactTab.getContent();
        HBox exactActions = (HBox) exactContent.getChildren().get(0);
        Button start = (Button) exactActions.getChildren().get(0);
        UiActionTestSupport.fx(() -> { start.fire(); return null; });
        verify(dialogs).showWarning(anyString(), anyString());
        verify(exact, never()).scanAsync(any());
    }

    @Test
    void IntegrationCenterDialog_save_copyKey_refresh_inspectMetadata_clearKey_setSecretsHaveObservableEffects() throws Exception {
        IntegrationBackend backend = mock(IntegrationBackend.class);
        UiBackgroundExecutor background = mock(UiBackgroundExecutor.class);
        DialogService dialogs = mock(DialogService.class);
        LocalizationService i18n = mock(LocalizationService.class);
        LocalMetadataExtractionService localMetadata = mock(LocalMetadataExtractionService.class);
        IntegrationBackend.WebDavConfiguration webDav = new IntegrationBackend.WebDavConfiguration(
                "https://example.invalid/dav", "alice", "sync-secret", true, true, "test-store", "device-1");
        when(backend.webDavConfiguration()).thenReturn(webDav);
        when(backend.saveWebDavConfiguration(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(IntegrationBackend.Result.ok("common.success"));
        when(dialogs.showConfirmation(anyString(), anyString(), anyString())).thenReturn(true);
        when(backend.clearWebDavConfiguration()).thenReturn(IntegrationBackend.Result.ok("common.success"));
        when(backend.plugins()).thenReturn(List.of());
        when(localMetadata.hasExtractors()).thenReturn(false);
        IntegrationBackend.AiConfiguration ai = new IntegrationBackend.AiConfiguration(
                "https://ai.invalid", "model", 1024, true, true, "test-store");
        when(backend.aiConfiguration()).thenReturn(ai);
        when(backend.aiProviders()).thenReturn(List.of());
        when(backend.clearAiApiKey()).thenReturn(IntegrationBackend.Result.ok("common.success"));
        IntegrationCenterDialog service = new IntegrationCenterDialog(backend, background, dialogs, i18n, localMetadata, mock(com.myhomelibcorp.ui.service.FileChooserService.class));

        Node webDavPane = UiActionTestSupport.fx(() -> (Node) invoke(service, "webDavPane", Window.class, null));
        List<Button> webButtons = UiActionTestSupport.fx(() -> descendants((Parent) webDavPane).stream()
                .filter(Button.class::isInstance).map(Button.class::cast).toList());
        assertThat(webButtons).hasSizeGreaterThanOrEqualTo(5);
        Button save = webButtons.get(0);
        Button copyKey = webButtons.get(3);
        Button clear = webButtons.get(4);
        UiActionTestSupport.fx(() -> { save.fire(); copyKey.fire(); clear.fire(); return null; });
        verify(backend).saveWebDavConfiguration(eq("https://example.invalid/dav"), eq("alice"), eq(""), eq(""));
        verify(backend).clearWebDavConfiguration();
        assertThat(UiActionTestSupport.fx(() -> Clipboard.getSystemClipboard().getString())).isEqualTo("sync-secret");
        verify(dialogs, atLeast(2)).showInfo(anyString(), anyString());

        Node pluginsPane = UiActionTestSupport.fx(() -> (Node) invoke(service, "pluginsPane", Window.class, null));
        List<Button> pluginButtons = UiActionTestSupport.fx(() -> descendants((Parent) pluginsPane).stream()
                .filter(Button.class::isInstance).map(Button.class::cast).toList());
        assertThat(pluginButtons).hasSizeGreaterThanOrEqualTo(6);
        Button refresh = pluginButtons.get(4);
        Button inspectMetadata = pluginButtons.get(5);
        UiActionTestSupport.fx(() -> { refresh.fire(); inspectMetadata.fire(); return null; });
        verify(backend, atLeast(2)).plugins();
        verify(localMetadata).hasExtractors();

        Node aiPane = UiActionTestSupport.fx(() -> (Node) invoke(service, "aiPane", Window.class, null));
        List<Button> aiButtons = UiActionTestSupport.fx(() -> descendants((Parent) aiPane).stream()
                .filter(Button.class::isInstance).map(Button.class::cast).toList());
        assertThat(aiButtons).hasSizeGreaterThanOrEqualTo(4);
        Button clearKey = aiButtons.get(1);
        Button setSecrets = aiButtons.get(2);
        UiActionTestSupport.fx(() -> { clearKey.fire(); setSecrets.fire(); return null; });
        verify(backend).clearAiApiKey();
        verify(dialogs).showWarning(anyString(), anyString());
    }

    @Test
    void IntegrationCenterDialog_installAndDisablePluginActionsReachBackend() throws Exception {
        IntegrationBackend backend = mock(IntegrationBackend.class);
        UiBackgroundExecutor background = mock(UiBackgroundExecutor.class);
        DialogService dialogs = mock(DialogService.class);
        LocalizationService i18n = mock(LocalizationService.class);
        LocalMetadataExtractionService localMetadata = mock(LocalMetadataExtractionService.class);
        com.myhomelibcorp.ui.service.FileChooserService chooser = mock(com.myhomelibcorp.ui.service.FileChooserService.class);
        java.io.File jar = new java.io.File("/tmp/myhomelib-test-plugin.jar");
        IntegrationBackend.PluginInfo plugin = new IntegrationBackend.PluginInfo(
                "plugin-1", "Plugin One", "1.0", "ENABLED", "TRUSTED", List.of(), List.of(),
                jar.getAbsolutePath(), "", "abc123", true);
        when(backend.plugins()).thenReturn(List.of(plugin));
        when(backend.installPlugin(jar.toPath())).thenReturn(IntegrationBackend.Result.ok("common.success"));
        when(backend.disablePlugin("plugin-1")).thenReturn(IntegrationBackend.Result.ok("common.success"));
        when(chooser.chooseFile(isNull(), anyString(), anyList())).thenReturn(jar);
        IntegrationCenterDialog service = new IntegrationCenterDialog(backend, background, dialogs, i18n, localMetadata, chooser);

        Node pluginsPane = UiActionTestSupport.fx(() -> (Node) invoke(service, "pluginsPane", Window.class, null));
        List<Button> pluginButtons = UiActionTestSupport.fx(() -> descendants((Parent) pluginsPane).stream()
                .filter(Button.class::isInstance).map(Button.class::cast).toList());
        assertThat(pluginButtons).hasSizeGreaterThanOrEqualTo(6);
        Button install = pluginButtons.get(0);
        Button disable = pluginButtons.get(2);
        UiActionTestSupport.fx(() -> { install.fire(); disable.fire(); return null; });

        verify(backend).installPlugin(jar.toPath());
        verify(backend).disablePlugin("plugin-1");
        verify(backend, atLeast(3)).plugins();
    }

    @Test
    void IntegrationCenterDialog_deleteSecretDeletesChosenProviderSecret() throws Exception {
        IntegrationBackend backend = mock(IntegrationBackend.class);
        UiBackgroundExecutor background = mock(UiBackgroundExecutor.class);
        DialogService dialogs = mock(DialogService.class);
        LocalizationService i18n = mock(LocalizationService.class);
        LocalMetadataExtractionService localMetadata = mock(LocalMetadataExtractionService.class);
        IntegrationBackend.AiConfiguration ai = new IntegrationBackend.AiConfiguration(
                "https://ai.invalid", "model", 1024, true, true, "test-store");
        IntegrationBackend.AiProviderInfo provider = new IntegrationBackend.AiProviderInfo(
                "provider-1", "Provider", List.of("summarize"), true, List.of("API_KEY"));
        when(backend.aiConfiguration()).thenReturn(ai);
        when(backend.aiProviders()).thenReturn(List.of(provider));
        when(dialogs.showChoiceDialog(anyList(), any(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of("API_KEY"));
        when(backend.deleteAiProviderSecret("provider-1", "API_KEY"))
                .thenReturn(IntegrationBackend.Result.ok("common.success"));
        IntegrationCenterDialog service = new IntegrationCenterDialog(backend, background, dialogs, i18n, localMetadata,
                mock(com.myhomelibcorp.ui.service.FileChooserService.class));

        Node aiPane = UiActionTestSupport.fx(() -> (Node) invoke(service, "aiPane", Window.class, null));
        List<Button> aiButtons = UiActionTestSupport.fx(() -> descendants((Parent) aiPane).stream()
                .filter(Button.class::isInstance).map(Button.class::cast).toList());
        Button deleteSecret = aiButtons.get(3);
        UiActionTestSupport.fx(() -> { deleteSecret.fire(); return null; });

        verify(backend).deleteAiProviderSecret("provider-1", "API_KEY");
        verify(dialogs).showChoiceDialog(anyList(), eq("API_KEY"), anyString(), eq("Provider"), anyString());
    }

    @Test
    void BulkMetadataEditUiService_removeDeletesSelectedRuleAndDisablesApplyAgain() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(BulkMetadataEditUiService.class);
        LocalizationService i18n = fixture.dependency(LocalizationService.class);
        when(i18n.text(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(i18n.format(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        Method editRules = BulkMetadataEditUiService.class.getDeclaredMethod("editRules", Window.class);
        editRules.setAccessible(true);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        Platform.runLater(() -> {
            try { result.set(editRules.invoke(fixture.instance(), new Object[]{null})); }
            catch (Throwable t) { error.set(t.getCause() == null ? t : t.getCause()); }
            finally { done.countDown(); }
        });
        Platform.runLater(() -> {
            try {
                DialogPane pane = latestDialogPane();
                VBox content = (VBox) pane.getContent();
                javafx.scene.layout.GridPane form = (javafx.scene.layout.GridPane) content.getChildren().get(0);
                @SuppressWarnings("unchecked") ListView<BatchMetadataEditRule> rules = (ListView<BatchMetadataEditRule>) content.getChildren().get(2);
                Button remove = (Button) content.getChildren().get(3);
                Button add = form.getChildren().stream().filter(Button.class::isInstance).map(Button.class::cast).findFirst().orElseThrow();
                add.fire();
                assertThat(rules.getItems()).hasSize(1);
                rules.getSelectionModel().selectFirst();
                assertThat(remove.isDisabled()).isFalse();
                remove.fire();
                assertThat(rules.getItems()).isEmpty();
                ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
            } catch (Throwable t) { error.set(t); }
        });
        assertThat(done.await(8, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) throw new AssertionError(error.get());
        assertThat((Optional<?>) result.get()).isEmpty();
    }

    private static Object invoke(Object target, String name, Class<?> parameterType, Object argument) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterType); method.setAccessible(true); return method.invoke(target, argument);
    }
    private static Object invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(target);
    }
    private static DialogPane latestDialogPane() {
        List<Window> windows = Window.getWindows().stream().filter(Window::isShowing).toList();
        for (int i = windows.size() - 1; i >= 0; i--) {
            Node root = windows.get(i).getScene() == null ? null : windows.get(i).getScene().getRoot();
            if (root instanceof DialogPane pane) return pane;
        }
        throw new AssertionError("No visible DialogPane");
    }
    private static List<Node> descendants(Parent parent) {
        ArrayList<Node> out = new ArrayList<>();
        if (parent instanceof ScrollPane scroll && scroll.getContent() != null) { Node content = scroll.getContent(); out.add(content); if (content instanceof Parent p) out.addAll(descendants(p)); }
        for (Node child : parent.getChildrenUnmodifiable()) { out.add(child); if (child instanceof Parent p) out.addAll(descendants(p)); }
        return out;
    }
}
