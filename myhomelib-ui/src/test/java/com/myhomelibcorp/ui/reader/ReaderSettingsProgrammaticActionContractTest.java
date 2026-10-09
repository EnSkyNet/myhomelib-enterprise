package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.reader.ReaderSettingsState;
import com.myhomelibcorp.reader.api.ReaderElementStyle;
import com.myhomelibcorp.reader.api.ReaderSemanticElement;
import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.api.ReaderSettingsPreset;
import com.myhomelibcorp.reader.api.ReaderStyleSheet;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReaderSettingsProgrammaticActionContractTest {
    @BeforeAll static void startFx() throws Exception { UiActionTestSupport.startFx(); }

    @Test
    void applyPresetUpdatesLivePreviewBeforeDialogAcceptance() throws Exception {
        LocalizationService i18n = mock(LocalizationService.class);
        when(i18n.text(anyString())).thenAnswer(inv -> inv.getArgument(0));
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<ReaderSettings> preview = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();

        javafx.application.Platform.runLater(() -> {
            try { ReaderSettingsDialog.show(null, ReaderSettings.defaultSettings(), false, preview::set, i18n); }
            catch (Throwable t) { error.set(t); }
            finally { done.countDown(); }
        });
        javafx.application.Platform.runLater(() -> {
            try {
                javafx.scene.control.DialogPane pane = javafx.stage.Window.getWindows().stream()
                        .filter(javafx.stage.Window::isShowing)
                        .map(w -> w.getScene() == null ? null : w.getScene().getRoot())
                        .filter(javafx.scene.control.DialogPane.class::isInstance)
                        .map(javafx.scene.control.DialogPane.class::cast)
                        .reduce((a, b) -> b).orElseThrow();
                javafx.scene.layout.BorderPane content = (javafx.scene.layout.BorderPane) pane.getContent();
                javafx.scene.layout.VBox header = (javafx.scene.layout.VBox) content.getTop();
                javafx.scene.layout.HBox presetRow = (javafx.scene.layout.HBox) header.getChildren().get(0);
                @SuppressWarnings("unchecked") javafx.scene.control.ComboBox<ReaderSettingsPreset> preset =
                        (javafx.scene.control.ComboBox<ReaderSettingsPreset>) presetRow.getChildren().get(1);
                javafx.scene.control.Button applyPreset = (javafx.scene.control.Button) presetRow.getChildren().get(2);
                assertThat(preset.getItems()).isNotEmpty();
                preset.setValue(preset.getItems().get(0));
                applyPreset.fire();
                assertThat(preview.get()).isEqualTo(preset.getValue().settings());
                ((javafx.scene.control.Button) pane.lookupButton(javafx.scene.control.ButtonType.CANCEL)).fire();
            } catch (Throwable t) { error.set(t); }
        });
        assertThat(done.await(8, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) throw new AssertionError(error.get());
    }


    @Test
    void scopedProfileButtonsReturnTheExactRequestedSaveTarget() throws Exception {
        // Keep the concrete programmatic action identifiers in behavioral evidence.
        String saveGlobalButton = "ui.reader.settings.scope.save_global";
        String saveFormatButton = "ui.reader.settings.scope.save_format";
        String saveSeriesButton = "ui.reader.settings.scope.save_series";
        String saveBookButton = "ui.reader.settings.scope.save_book";
        String clearOverrideButton = "ui.reader.settings.scope.clear";

        assertScopeAction(saveGlobalButton, ReaderSettingsDialog.SaveTarget.GLOBAL);
        assertScopeAction(saveFormatButton, ReaderSettingsDialog.SaveTarget.FORMAT);
        assertScopeAction(saveSeriesButton, ReaderSettingsDialog.SaveTarget.SERIES);
        assertScopeAction(saveBookButton, ReaderSettingsDialog.SaveTarget.BOOK);
        assertScopeAction(clearOverrideButton, ReaderSettingsDialog.SaveTarget.CLEAR_OVERRIDE);
    }

    private static void assertScopeAction(String buttonText, ReaderSettingsDialog.SaveTarget expected) throws Exception {
        LocalizationService i18n = mock(LocalizationService.class);
        when(i18n.text(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(i18n.format(anyString(), org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<OptionalResult> observed = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();

        javafx.application.Platform.runLater(() -> {
            try {
                var result = ReaderSettingsDialog.show(null, ReaderSettings.defaultSettings(),
                        new ReaderSettingsDialog.ScopeContext(true, true, true, ReaderSettingsState.Source.SERIES),
                        ignored -> {}, i18n);
                observed.set(new OptionalResult(result.orElseThrow().target()));
            } catch (Throwable t) { error.set(t); }
            finally { done.countDown(); }
        });
        javafx.application.Platform.runLater(() -> {
            try {
                javafx.scene.control.DialogPane pane = javafx.stage.Window.getWindows().stream()
                        .filter(javafx.stage.Window::isShowing)
                        .map(w -> w.getScene() == null ? null : w.getScene().getRoot())
                        .filter(javafx.scene.control.DialogPane.class::isInstance)
                        .map(javafx.scene.control.DialogPane.class::cast)
                        .reduce((a, b) -> b).orElseThrow();
                javafx.scene.control.Button button = pane.getButtonTypes().stream()
                        .filter(type -> buttonText.equals(type.getText()))
                        .map(pane::lookupButton)
                        .filter(javafx.scene.control.Button.class::isInstance)
                        .map(javafx.scene.control.Button.class::cast)
                        .findFirst().orElseThrow();
                assertThat(button.isDisable()).isFalse();
                button.fire();
            } catch (Throwable t) { error.set(t); }
        });
        assertThat(done.await(8, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) throw new AssertionError(error.get());
        assertThat(observed.get()).isNotNull();
        assertThat(observed.get().target()).isEqualTo(expected);
    }

    private record OptionalResult(ReaderSettingsDialog.SaveTarget target) {}

    @Test
    void resetSelectedSemanticTypographyRestoresTypographyWhilePreservingColor() throws Exception {
        Object controls = controls();
        @SuppressWarnings("unchecked") ComboBox<ReaderSemanticElement> semanticElement = (ComboBox<ReaderSemanticElement>) field(controls, "semanticElement");
        UiActionTestSupport.fx(() -> { semanticElement.setValue(ReaderSemanticElement.BOOK_TITLE); return null; });
        Object styleSheet = field(controls, "styleSheet");
        Method with = styleSheet.getClass().getMethod("with", ReaderSemanticElement.class, ReaderElementStyle.class);
        ReaderElementStyle custom = new ReaderElementStyle("Courier New", 31.0, 1.7, "bold", "#112233", "center", 5.0, 6.0);
        setField(controls, "styleSheet", with.invoke(styleSheet, ReaderSemanticElement.BOOK_TITLE, custom));

        UiActionTestSupport.fx(() -> { invoke(controls, "resetSelectedSemanticTypography"); return null; });

        ReaderStyleSheet after = (ReaderStyleSheet) field(controls, "styleSheet");
        ReaderElementStyle reset = after.styles().get(ReaderSemanticElement.BOOK_TITLE);
        ReaderElementStyle defaults = ReaderStyleSheet.defaults().styles().get(ReaderSemanticElement.BOOK_TITLE);
        assertThat(reset.fontFamily()).isEqualTo(defaults.fontFamily());
        assertThat(reset.color()).isEqualTo("#112233");
    }

    @Test
    void inherit_clearSemanticColorsRemovesExplicitOverrides() throws Exception {
        Object controls = controls();
        @SuppressWarnings("unchecked") Set<ReaderSemanticElement> explicit = (Set<ReaderSemanticElement>) field(controls, "explicitSemanticColors");
        explicit.add(ReaderSemanticElement.LINK);
        ReaderStyleSheet styleSheet = (ReaderStyleSheet) field(controls, "styleSheet");
        ReaderElementStyle current = styleSheet.styles().get(ReaderSemanticElement.LINK);
        setField(controls, "styleSheet", styleSheet.with(ReaderSemanticElement.LINK, current.withColor("#abcdef")));

        UiActionTestSupport.fx(() -> { invoke(controls, "clearSemanticColors"); return null; });

        assertThat(explicit).isEmpty();
        ReaderStyleSheet after = (ReaderStyleSheet) field(controls, "styleSheet");
        assertThat(after.styles().get(ReaderSemanticElement.LINK).color()).isEmpty();
    }

    @Test
    void textColor_refreshInheritedSemanticColorsPropagatesGlobalForegroundToInheritedElements() throws Exception {
        Object controls = controls();
        ColorPicker textColor = (ColorPicker) field(controls, "textColor");
        @SuppressWarnings("unchecked") Map<ReaderSemanticElement, ColorPicker> semanticColors =
                (Map<ReaderSemanticElement, ColorPicker>) field(controls, "semanticColors");
        UiActionTestSupport.fx(() -> { textColor.setValue(Color.web("#224466")); invoke(controls, "refreshInheritedSemanticColors"); return null; });

        Color inherited = UiActionTestSupport.fx(() -> semanticColors.get(ReaderSemanticElement.BOOK_TITLE).getValue());
        assertThat(inherited.getRed()).isCloseTo(Color.web("#224466").getRed(), org.assertj.core.data.Offset.offset(0.001));
        assertThat(inherited.getGreen()).isCloseTo(Color.web("#224466").getGreen(), org.assertj.core.data.Offset.offset(0.001));
    }

    private static Object controls() throws Exception {
        LocalizationService i18n = mock(LocalizationService.class);
        when(i18n.text(anyString())).thenAnswer(inv -> inv.getArgument(0));
        Class<?> type = java.util.Arrays.stream(ReaderSettingsDialog.class.getDeclaredClasses())
                .filter(c -> c.getSimpleName().equals("Controls")).findFirst().orElseThrow();
        Constructor<?> ctor = type.getDeclaredConstructor(ReaderSettings.class, boolean.class, LocalizationService.class);
        ctor.setAccessible(true);
        return UiActionTestSupport.fx(() -> ctor.newInstance(ReaderSettings.defaultSettings(), false, i18n));
    }
    private static Object invoke(Object target, String name) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name); m.setAccessible(true); return m.invoke(target);
    }
    private static Object field(Object target, String name) throws Exception { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
    private static void setField(Object target, String name, Object value) throws Exception { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target,value); }
}
