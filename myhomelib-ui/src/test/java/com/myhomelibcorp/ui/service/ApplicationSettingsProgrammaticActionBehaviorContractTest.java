package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.conversion.diagnostics.ConverterDiagnosticsPort;
import com.myhomelibcorp.application.conversion.diagnostics.ConverterToolStatus;
import com.myhomelibcorp.application.conversion.diagnostics.EpubCheckDiagnosticsPort;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApplicationSettingsProgrammaticActionBehaviorContractTest {
    @BeforeAll
    static void startFx() throws Exception { UiActionTestSupport.startFx(); }

    @Test
    void languageDiagnostics_and_diagnosticsButtonsExposeDiagnosticStateWithoutExternalSideEffects() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(ApplicationSettingsDialog.class);
        LocalizationService i18n = fixture.dependency(LocalizationService.class);
        SupportBundleService support = fixture.dependency(SupportBundleService.class);
        when(i18n.availableLanguages()).thenReturn(Map.of("uk", "Українська"));
        when(i18n.language()).thenReturn("uk");
        when(i18n.text(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(i18n.languageDiagnostics()).thenReturn(List.of("WARN sample"));
        when(i18n.languageDiagnosticsFile()).thenReturn(Path.of("language-diagnostics.txt"));
        when(support.preview(any())).thenReturn(new SupportBundlePreview(List.of()));

        Method generalPane = ApplicationSettingsDialog.class.getDeclaredMethod("generalPane", Map.class);
        generalPane.setAccessible(true);
        ScrollPane pane = UiActionTestSupport.fx(() -> (ScrollPane) generalPane.invoke(fixture.instance(), new LinkedHashMap<String, CheckBox>()));
        VBox content = (VBox) pane.getContent();
        HBox actions = (HBox) content.getChildren().get(content.getChildren().size() - 1);
        Button languageDiagnostics = (Button) actions.getChildren().get(0);
        Button diagnostics = (Button) actions.getChildren().get(1);

        UiActionTestSupport.fx(() -> {
            Platform.runLater(ApplicationSettingsProgrammaticActionBehaviorContractTest::hideShowingWindows);
            languageDiagnostics.fire();
            return null;
        });
        verify(i18n).languageDiagnostics();
        verify(i18n, never()).languageDiagnosticsFile();

        UiActionTestSupport.fx(() -> {
            Platform.runLater(ApplicationSettingsProgrammaticActionBehaviorContractTest::hideShowingWindows);
            diagnostics.fire();
            return null;
        });
        verify(support, atLeastOnce()).preview(any());
    }

    @Test
    void refreshConverterDiagnosticsRunsProbeAndUpdatesDiagnosticsArea() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(ApplicationSettingsDialog.class);
        LocalizationService i18n = fixture.dependency(LocalizationService.class);
        ConverterDiagnosticsPort converterDiagnostics = fixture.dependency(ConverterDiagnosticsPort.class);
        EpubCheckDiagnosticsPort epubCheckDiagnostics = fixture.dependency(EpubCheckDiagnosticsPort.class);
        UiBackgroundExecutor background = fixture.dependency(UiBackgroundExecutor.class);
        when(i18n.text(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(converterDiagnostics.snapshot()).thenReturn(List.of());
        when(epubCheckDiagnostics.snapshot()).thenReturn(null);
        ConverterToolStatus status = new ConverterToolStatus("calibre", true, "7.0", "/tool", "ok", java.time.Instant.parse("2026-09-29T00:00:00Z"));
        when(converterDiagnostics.probe(any())).thenReturn(List.of(status));
        when(epubCheckDiagnostics.probe(anyString())).thenReturn(null);
        when(background.submit(any(Callable.class))).thenAnswer(inv -> {
            Callable<?> task = inv.getArgument(0);
            try { return CompletableFuture.completedFuture(task.call()); }
            catch (Exception error) { return CompletableFuture.failedFuture(error); }
        });

        Object editor = newConverterEditor(fixture.instance());
        VBox pane = (VBox) UiActionTestSupport.getField(editor, "pane");
        Button refresh = UiActionTestSupport.fx(() -> descendants(pane).stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> "ui.settings.converters.check".equals(button.getText()))
                .findFirst().orElseThrow());
        TextArea diagnosticsArea = (TextArea) UiActionTestSupport.getField(editor, "diagnosticsArea");
        UiActionTestSupport.fx(() -> { refresh.fire(); return null; });
        UiActionTestSupport.fx(() -> null); // flush UiExecutor.runOnUiThread

        verify(converterDiagnostics).probe(any());
        String diagnosticsText = UiActionTestSupport.fx(diagnosticsArea::getText);
        assertThat(diagnosticsText).containsIgnoringCase("calibre").contains("7.0");
        assertThat(UiActionTestSupport.fx(refresh::isDisabled)).isFalse();
    }

    @Test
    void browseUsesFileChooserBoundaryAndUpdatesExecutableField() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(ApplicationSettingsDialog.class);
        LocalizationService i18n = fixture.dependency(LocalizationService.class);
        FileChooserService chooser = fixture.dependency(FileChooserService.class);
        when(i18n.text(anyString())).thenAnswer(inv -> inv.getArgument(0));
        java.nio.file.Path chosen = java.nio.file.Files.createTempFile("mhl-converter-", ".bin");
        when(chooser.chooseFile(any(), anyString(), anyList(), any())).thenReturn(chosen.toFile());

        Object editor = newConverterEditor(fixture.instance());
        VBox pane = (VBox) UiActionTestSupport.getField(editor, "pane");
        Button browse = UiActionTestSupport.fx(() -> descendants(pane).stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> "ui.settings.converters.browse".equals(button.getText()))
                .findFirst().orElseThrow());
        HBox row = (HBox) browse.getParent();
        javafx.scene.control.TextField executable = (javafx.scene.control.TextField) row.getChildren().get(0);

        UiActionTestSupport.fx(() -> { browse.fire(); return null; });

        String selectedPath = UiActionTestSupport.fx(executable::getText);
        assertThat(selectedPath).isEqualTo(chosen.toAbsolutePath().normalize().toString());
        verify(chooser).chooseFile(any(), eq("ui.settings.converters.choose_executable"), anyList(), any());
    }

    private static Object newConverterEditor(ApplicationSettingsDialog dialog) throws Exception {
        Class<?> type = java.util.Arrays.stream(ApplicationSettingsDialog.class.getDeclaredClasses())
                .filter(candidate -> candidate.getSimpleName().equals("ConverterSettingsEditor"))
                .findFirst().orElseThrow();
        Constructor<?> ctor = type.getDeclaredConstructor(ApplicationSettingsDialog.class, Map.class);
        ctor.setAccessible(true);
        return UiActionTestSupport.fx(() -> ctor.newInstance(dialog, new LinkedHashMap<String, javafx.scene.control.TextField>()));
    }

    private static List<Node> descendants(Parent root) {
        java.util.ArrayList<Node> out = new java.util.ArrayList<>();
        java.util.ArrayDeque<Parent> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Parent parent = queue.removeFirst();
            for (Node child : parent.getChildrenUnmodifiable()) {
                out.add(child);
                if (child instanceof Parent nested) queue.addLast(nested);
            }
        }
        return out;
    }

    private static void hideShowingWindows() {
        java.util.List.copyOf(Window.getWindows()).stream().filter(Window::isShowing).forEach(Window::hide);
    }
}
