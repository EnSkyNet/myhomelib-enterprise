package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.update.ReleaseManifest;
import com.myhomelibcorp.application.update.UpdateChannel;
import com.myhomelibcorp.application.conversion.diagnostics.ConverterDiagnosticsPort;
import com.myhomelibcorp.application.conversion.diagnostics.ConverterDiagnosticsRequest;
import com.myhomelibcorp.application.conversion.diagnostics.ConverterToolStatus;
import com.myhomelibcorp.application.conversion.diagnostics.EpubCheckDiagnosticsPort;
import com.myhomelibcorp.application.content.indexing.IndexingPerformanceSettings;
import com.myhomelibcorp.application.content.indexing.IndexingPerformanceSettingsService;
import com.myhomelibcorp.application.content.indexing.IndexingResourceProfile;
import com.myhomelibcorp.application.util.CommandTemplate;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.EncryptionUtil;
import com.myhomelibcorp.shared.util.ExecutablePathSafety;
import com.myhomelibcorp.shared.util.ProcessExecutionSupport;
import com.myhomelibcorp.shared.format.SupportedFormat;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/**
 * Central settings dialog. Values are stored through ApplicationSettingsPort, so
 * portable mode writes them next to the application instead of the user profile.
 */
@Component
public class ApplicationSettingsDialog {
    private final ApplicationSettingsPort settings;
    private final SupportBundleService supportBundleService;
    private final LocalizationService localizationService;
    private final ApplicationThemeService themeService;
    private final IndexingPerformanceSettingsService indexingPerformanceSettingsService;
    private final UiBackgroundExecutor backgroundExecutor;
    private final ConverterDiagnosticsPort converterDiagnostics;
    private final EpubCheckDiagnosticsPort epubCheckDiagnostics;
    private final FileChooserService fileChooserService;
    private final ApplicationUpdateUiService applicationUpdateUiService;

    public ApplicationSettingsDialog(ApplicationSettingsPort settings,
                                     SupportBundleService supportBundleService,
                                     LocalizationService localizationService,
                                     ApplicationThemeService themeService,
                                     IndexingPerformanceSettingsService indexingPerformanceSettingsService,
                                     UiBackgroundExecutor backgroundExecutor,
                                     ConverterDiagnosticsPort converterDiagnostics,
                                     EpubCheckDiagnosticsPort epubCheckDiagnostics,
                                     FileChooserService fileChooserService,
                                     ApplicationUpdateUiService applicationUpdateUiService) {
        this.settings = settings;
        this.supportBundleService = supportBundleService;
        this.localizationService = localizationService;
        this.themeService = themeService;
        this.indexingPerformanceSettingsService = indexingPerformanceSettingsService;
        this.backgroundExecutor = backgroundExecutor;
        this.converterDiagnostics = converterDiagnostics;
        this.epubCheckDiagnostics = epubCheckDiagnostics;
        this.fileChooserService = fileChooserService;
        this.applicationUpdateUiService = applicationUpdateUiService;
    }

    public void show(Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        DialogLocalization.prepare(dialog);
        dialog.setTitle(localizationService.text("ui.settings.title"));
        dialog.setHeaderText(localizationService.text("ui.settings.header"));
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Map<String, TextField> text = new LinkedHashMap<>();
        Map<String, PasswordField> secrets = new LinkedHashMap<>();
        Set<String> unreadableSecrets = new HashSet<>();
        Map<String, CheckBox> bool = new LinkedHashMap<>();

        ApplicationThemeService.ThemeConfig originalTheme = themeService.current();
        ThemeEditor themeEditor = new ThemeEditor(originalTheme);
        IndexingSettingsEditor indexingEditor = new IndexingSettingsEditor(indexingPerformanceSettingsService.load());
        ConverterSettingsEditor converterEditor = new ConverterSettingsEditor(text);
        UpdateSettingsEditor updateEditor = new UpdateSettingsEditor(text, bool);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(tab(localizationService.text("ui.settings.tab.general"), generalPane(bool)));
        tabs.getTabs().add(tab(localizationService.text("ui.settings.tab.theme"), themeEditor.pane()));
        tabs.getTabs().add(tab(localizationService.text("ui.settings.tab.indexing"), indexingEditor.pane()));
        tabs.getTabs().add(tab(localizationService.text("ui.settings.tab.external_reading"), externalReadersPane(text)));
        tabs.getTabs().add(tab(localizationService.text("ui.settings.tab.converters"), converterEditor.pane()));
        tabs.getTabs().add(tab(localizationService.text("ui.settings.tab.device_export"), devicePane(text)));
        tabs.getTabs().add(tab(localizationService.text("ui.settings.tab.updates"), updateEditor.pane()));
        tabs.getTabs().add(tab(localizationService.text("ui.settings.tab.online"), onlinePane(text, secrets, unreadableSecrets, bool)));
        tabs.setPrefSize(790, 590);
        dialog.getDialogPane().setContent(tabs);
        Node okButton = dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            String validation = converterEditor.validationMessage();
            if (validation == null || validation.isBlank()) return;
            event.consume();
            Alert alert = new Alert(Alert.AlertType.WARNING, validation, ButtonType.OK);
            DialogLocalization.prepare(alert);
            alert.setTitle(localizationService.text("ui.settings.title"));
            alert.setHeaderText(localizationService.text("ui.settings.converters.validation.title"));
            if (owner != null) alert.initOwner(owner);
            alert.showAndWait();
        });

        boolean accepted = dialog.showAndWait().filter(ButtonType.OK::equals).isPresent();
        if (!accepted) {
            themeService.apply(originalTheme);
            secrets.values().forEach(PasswordField::clear);
            return;
        }

        text.forEach((key, field) -> settings.put(key, field.getText() == null ? "" : field.getText().trim()));
        synchronizeCalibreRuntimeProperties();
        secrets.forEach((key, field) -> {
            if (unreadableSecrets.contains(key)) {
                field.clear();
                return;
            }
            String plain = field.getText() == null ? "" : field.getText();
            if (plain.isBlank()) settings.remove(key);
            else settings.put(key, EncryptionUtil.encrypt(plain));
            field.clear();
        });
        bool.forEach((key, check) -> settings.putBoolean(key, check.isSelected()));
        themeService.save(themeEditor.config());
        indexingPerformanceSettingsService.save(indexingEditor.value());
        converterEditor.save();
        updateEditor.save();
    }

    private void synchronizeCalibreRuntimeProperties() {
        String executable = settings.get("converter.calibre.executable", "").trim();
        String timeout = settings.get("converter.calibre.timeoutSeconds", "1800").trim();
        if (executable.isBlank()) System.clearProperty("myhomelib.calibre.executable");
        else System.setProperty("myhomelib.calibre.executable", executable);
        System.setProperty("myhomelib.calibre.timeoutSeconds", timeout.isBlank() ? "1800" : timeout);
    }

    private Tab tab(String title, Node content) { return new Tab(title, content); }

    private Node generalPane(Map<String, CheckBox> bool) {
        VBox box = section();
        ComboBox<LanguageOption> lang = new ComboBox<>();
        var availableLanguages = localizationService.availableLanguages();
        availableLanguages.forEach((code, name) -> lang.getItems().add(new LanguageOption(code, name)));
        String selectedCode = localizationService.language();
        lang.getItems().stream()
                .filter(option -> option.code().equals(selectedCode))
                .findFirst()
                .ifPresent(lang::setValue);
        if (lang.getValue() == null && !lang.getItems().isEmpty()) lang.setValue(lang.getItems().getFirst());
        lang.valueProperty().addListener((o, a, b) -> {
            if (b != null) localizationService.setLanguage(b.code());
        });
        CheckBox confirmDelete = checkbox(bool, "ui.confirmDelete", UiText.text("ui.java.application_settings_dialog.1c86dde74a"), true);
        CheckBox restoreSession = checkbox(bool, "ui.restoreSession", UiText.text("ui.java.application_settings_dialog.7be947f1bc"), true);
        CheckBox reducedMotion = checkbox(bool, "ui.accessibility.reducedMotion", UiText.text("ui.accessibility.reducedMotion"), false);
        Label paths = new Label(UiText.text("ui.java.application_settings_dialog.1e1438aaa7") + AppPaths.dataDir() + UiText.text("ui.java.application_settings_dialog.155954414d") + (AppPaths.portableMode() ? UiText.text("ui.java.application_settings_dialog.e8a6b3d79c") : UiText.text("ui.java.application_settings_dialog.6c7d49b919")));
        paths.setWrapText(true);
        Button languageDiagnostics = new Button(UiText.text("ui.java.application_settings_dialog.35c487ded3"));
        languageDiagnostics.setOnAction(e -> showLanguageDiagnostics());
        Button diagnostics = new Button(UiText.text("ui.java.application_settings_dialog.98494677ba"));
        diagnostics.setOnAction(e -> createSupportBundle(diagnostics.getScene() == null ? null : diagnostics.getScene().getWindow()));
        HBox diagnosticActions = new HBox(8, languageDiagnostics, diagnostics);
        box.getChildren().addAll(row(UiText.text("ui.java.application_settings_dialog.126af8fe9b"), lang), confirmDelete, restoreSession, reducedMotion, new Separator(), paths, diagnosticActions);
        return scroll(box);
    }

    private Node externalReadersPane(Map<String, TextField> text) {
        VBox box = section();
        box.getChildren().add(new Label(UiText.text("ui.java.application_settings_dialog.786b497797")));
        for (String ext : SupportedFormatRegistry.standard().extensions(
                f -> f.family() == SupportedFormat.Family.BOOK && f.importSupported())) {
            TextField command = field(text, "reader.external." + ext, "");
            box.getChildren().add(commandRow(ext.toUpperCase(Locale.ROOT), command, Map.of(
                    "%FILE%", samplePath("book." + ext), "%TITLE%", UiText.text("ui.java.application_settings_dialog.6ed732b2ca"),
                    "%AUTHOR%", localizationService.text("ui.settings.command_test.sample_author"))));
        }
        return scroll(box);
    }

    private final class ConverterSettingsEditor {
        private final Map<String, TextField> text;
        private final ComboBox<SettingOption> mode = new ComboBox<>();
        private final ComboBox<SettingOption> optimizationProfile = new ComboBox<>();
        private final ComboBox<SettingOption> readingSystemProfile = new ComboBox<>();
        private final ComboBox<SettingOption> acceptanceMode = new ComboBox<>();
        private final TextArea diagnosticsArea = new TextArea();
        private final VBox pane = section();
        private final TextField fbcExecutable;
        private final TextField pandocExecutable;
        private final TextField libmobiExecutable;
        private final TextField calibreExecutable;
        private final TextField epubcheckExecutable;

        ConverterSettingsEditor(Map<String, TextField> text) {
            this.text = text;
            this.fbcExecutable = field(text, "converter.toolchain.fbc.executable", "");
            this.pandocExecutable = field(text, "converter.toolchain.pandoc.executable", "");
            this.libmobiExecutable = field(text, "converter.toolchain.libmobi.executable", "");
            this.calibreExecutable = field(text, "converter.calibre.executable", "");
            this.epubcheckExecutable = field(text, "converter.epubcheck.executable", "");
            mode.getItems().setAll(
                    option("AUTO", "ui.settings.converters.mode.auto"),
                    option("TOOLCHAIN", "ui.settings.converters.mode.toolchain"),
                    option("CALIBRE", "ui.settings.converters.mode.calibre"),
                    option("MANUAL", "ui.settings.converters.mode.manual"));
            select(mode, settings.get("converter.mode", "AUTO"), "AUTO");

            optimizationProfile.getItems().setAll(
                    option("NONE", "ui.settings.converters.optimizer.none"),
                    option("COMPATIBLE_EPUB33", "ui.settings.converters.optimizer.compatible"),
                    option("BALANCED_EPUB33", "ui.settings.converters.optimizer.balanced"),
                    option("MAXIMUM_EPUB33", "ui.settings.converters.optimizer.maximum"),
                    option("MODERN_EPUB34", "ui.settings.converters.optimizer.modern"));
            select(optimizationProfile, settings.get("converter.epubOptimizationProfile", "BALANCED_EPUB33"), "BALANCED_EPUB33");

            readingSystemProfile.getItems().setAll(
                    option("GENERIC_EPUB33_COMPAT", "ui.settings.converters.reading.generic"),
                    option("MYHOMELIB_DESKTOP", "ui.settings.converters.reading.myhomelib"),
                    option("MODERN_EPUB33", "ui.settings.converters.reading.modern_epub33"),
                    option("KOBO", "ui.settings.converters.reading.kobo"),
                    option("POCKETBOOK", "ui.settings.converters.reading.pocketbook"),
                    option("KINDLE_DERIVED", "ui.settings.converters.reading.kindle"),
                    option("MODERN_EPUB34_EXPERIMENTAL", "ui.settings.converters.reading.epub34"));
            select(readingSystemProfile, settings.get("converter.epubReadingSystemProfile", "GENERIC_EPUB33_COMPAT"), "GENERIC_EPUB33_COMPAT");

            acceptanceMode.getItems().setAll(
                    option("STANDARD", "ui.settings.converters.acceptance.standard"),
                    option("STRICT", "ui.settings.converters.acceptance.strict"));
            select(acceptanceMode, settings.get("converter.epubAcceptanceMode", "STANDARD"), "STANDARD");

            Label intro = new Label(localizationService.text("ui.settings.converters.toolchain.info"));
            intro.setWrapText(true);
            Label calibreInfo = new Label(localizationService.text("ui.settings.converters.calibre.info"));
            calibreInfo.setWrapText(true);
            pane.getChildren().addAll(
                    intro,
                    row(localizationService.text("ui.settings.converters.mode"), mode),
                    new Separator(),
                    row(localizationService.text("ui.settings.converters.fbc.executable"), executableField(fbcExecutable)),
                    row(localizationService.text("ui.settings.converters.pandoc.executable"), executableField(pandocExecutable)),
                    row(localizationService.text("ui.settings.converters.libmobi.executable"), executableField(libmobiExecutable)),
                    row(localizationService.text("ui.settings.converters.calibre.executable"), executableField(calibreExecutable)),
                    row(localizationService.text("ui.settings.converters.calibre.timeout"),
                            field(text, "converter.calibre.timeoutSeconds", "1800")),
                    calibreInfo,
                    row(localizationService.text("ui.settings.converters.epubcheck.executable"), executableField(epubcheckExecutable)));

            Button refresh = new Button(localizationService.text("ui.settings.converters.check"));
            refresh.setOnAction(e -> refreshDiagnostics(refresh));
            diagnosticsArea.setEditable(false);
            diagnosticsArea.setWrapText(true);
            diagnosticsArea.setPrefRowCount(6);
            diagnosticsArea.setText(formatDiagnostics(withEpubCheck(converterDiagnostics.snapshot(), epubCheckDiagnostics.snapshot())));
            pane.getChildren().addAll(refresh, diagnosticsArea, new Separator(),
                    row(localizationService.text("ui.settings.converters.optimizer.profile"), optimizationProfile),
                    row(localizationService.text("ui.settings.converters.reading.profile"), readingSystemProfile),
                    row(localizationService.text("ui.settings.converters.acceptance.mode"), acceptanceMode),
                    new Separator());

            pane.getChildren().add(new Separator());
            pane.getChildren().add(new Label(localizationService.text("ui.settings.converters.external_templates.help")));
            addConverterRow(pane, text, localizationService.text("ui.settings.converters.fb2_to_epub"), "converter.epub.command", "epub");
            addConverterRow(pane, text, localizationService.text("ui.settings.converters.fb2_to_pdf"), "converter.pdf.command", "pdf");
            addConverterRow(pane, text, localizationService.text("ui.settings.converters.fb2_to_mobi"), "converter.mobi.command", "mobi");
            addConverterRow(pane, text, localizationService.text("ui.settings.converters.fb2_to_lrf"), "converter.lrf.command", "lrf");
            pane.getChildren().add(row(localizationService.text("ui.settings.converters.external.timeout"),
                    field(text, "converter.timeoutSeconds", "300")));
        }

        Node pane() { return scroll(pane); }

        String validationMessage() {
            String optimizer = selectedId(optimizationProfile, "BALANCED_EPUB33");
            String reader = selectedId(readingSystemProfile, "GENERIC_EPUB33_COMPAT");
            if ("MODERN_EPUB34".equals(optimizer) && !"MODERN_EPUB34_EXPERIMENTAL".equals(reader)) {
                return localizationService.text("ui.settings.converters.validation.epub34_profile");
            }
            return null;
        }

        void save() {
            settings.put("converter.mode", selectedId(mode, "AUTO"));
            settings.put("converter.epubOptimizationProfile", selectedId(optimizationProfile, "BALANCED_EPUB33"));
            settings.put("converter.epubReadingSystemProfile", selectedId(readingSystemProfile, "GENERIC_EPUB33_COMPAT"));
            settings.put("converter.epubAcceptanceMode", selectedId(acceptanceMode, "STANDARD"));
        }

        private void refreshDiagnostics(Button trigger) {
            trigger.setDisable(true);
            diagnosticsArea.setText(localizationService.text("ui.settings.converters.checking"));
            ConverterDiagnosticsRequest request = new ConverterDiagnosticsRequest(
                    fbcExecutable.getText(), pandocExecutable.getText(), libmobiExecutable.getText(),
                    calibreExecutable.getText());
            String epubcheck = epubcheckExecutable.getText();
            backgroundExecutor.submit(() -> withEpubCheck(converterDiagnostics.probe(request), epubCheckDiagnostics.probe(epubcheck)))
                    .whenComplete((result, error) -> UiExecutor.runOnUiThread(() -> {
                        trigger.setDisable(false);
                        if (error != null) diagnosticsArea.setText(localizationService.text("ui.settings.converters.check_failed") + " " + rootMessage(error));
                        else diagnosticsArea.setText(formatDiagnostics(result));
                    }));
        }

        private Node executableField(TextField executable) {
            Button browse = new Button(localizationService.text("ui.settings.converters.browse"));
            browse.setOnAction(event -> {
                boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
                java.util.List<FileChooser.ExtensionFilter> filters = windows
                        ? java.util.List.of(
                                new FileChooser.ExtensionFilter(
                                        localizationService.text("ui.settings.converters.executable_files"),
                                        "*.exe", "*.bat", "*.cmd"),
                                new FileChooser.ExtensionFilter("EPUBCheck JAR (*.jar)", "*.jar"),
                                new FileChooser.ExtensionFilter("All files (*.*)", "*.*"))
                        : java.util.List.of(
                                new FileChooser.ExtensionFilter(
                                        localizationService.text("ui.settings.converters.executable_files"), "*"),
                                new FileChooser.ExtensionFilter("EPUBCheck JAR (*.jar)", "*.jar"));
                java.io.File initialDirectory = null;
                try {
                    String value = executable.getText() == null ? "" : executable.getText().trim().replace("\"", "");
                    if (!value.isBlank()) {
                        java.nio.file.Path path = java.nio.file.Path.of(value).toAbsolutePath().normalize();
                        java.nio.file.Path parent = java.nio.file.Files.isDirectory(path) ? path : path.getParent();
                        if (parent != null && java.nio.file.Files.isDirectory(parent)) initialDirectory = parent.toFile();
                    }
                } catch (RuntimeException ignored) { }
                java.io.File selected = fileChooserService.chooseFile(
                        pane.getScene() == null ? null : pane.getScene().getWindow(),
                        localizationService.text("ui.settings.converters.choose_executable"), filters, initialDirectory);
                if (selected != null) executable.setText(selected.toPath().toAbsolutePath().normalize().toString());
            });
            HBox box = new HBox(8, executable, browse);
            HBox.setHgrow(executable, javafx.scene.layout.Priority.ALWAYS);
            return box;
        }

        private java.util.List<ConverterToolStatus> withEpubCheck(java.util.List<ConverterToolStatus> converters, ConverterToolStatus epubCheck) {
            java.util.ArrayList<ConverterToolStatus> result = new java.util.ArrayList<>();
            if (converters != null) result.addAll(converters);
            if (epubCheck != null) result.add(epubCheck);
            return java.util.List.copyOf(result);
        }

        private String formatDiagnostics(java.util.List<ConverterToolStatus> statuses) {
            if (statuses == null || statuses.isEmpty()) return localizationService.text("ui.settings.converters.no_diagnostics");
            StringBuilder out = new StringBuilder();
            for (ConverterToolStatus status : statuses) {
                if (!out.isEmpty()) out.append('\n');
                out.append(displayToolName(status.id())).append(": ")
                        .append(localizationService.text(status.available() ? "ui.settings.converters.status.available" : "ui.settings.converters.status.unavailable"));
                if (!status.version().isBlank()) out.append(" — ").append(status.version());
                if (!status.executablePath().isBlank()) out.append("\n  ").append(status.executablePath());
                if (!status.diagnostic().isBlank()) out.append("\n  ").append(status.diagnostic());
            }
            return out.toString();
        }

        private String displayToolName(String id) {
            return switch (id == null ? "" : id.toLowerCase(Locale.ROOT)) {
                case "fbc" -> "fb2cng (fbc)";
                case "pandoc" -> "Pandoc";
                case "libmobi" -> "libmobi (mobitool)";
                case "calibre" -> "Calibre (ebook-convert)";
                default -> id == null ? "" : id;
            };
        }

        private SettingOption option(String id, String key) { return new SettingOption(id, localizationService.text(key)); }

        private void select(ComboBox<SettingOption> combo, String id, String fallback) {
            combo.getItems().stream().filter(option -> option.id().equalsIgnoreCase(id)).findFirst()
                    .or(() -> combo.getItems().stream().filter(option -> option.id().equals(fallback)).findFirst())
                    .ifPresent(combo::setValue);
        }

        private String selectedId(ComboBox<SettingOption> combo, String fallback) {
            return combo.getValue() == null ? fallback : combo.getValue().id();
        }
    }

    private record SettingOption(String id, String label) {
        @Override public String toString() { return label; }
    }

    private Node devicePane(Map<String, TextField> text) {
        VBox box = section();
        box.getChildren().add(new Label(UiText.text("ui.java.application_settings_dialog.bebf1ce7a3")));
        box.getChildren().add(row(UiText.text("ui.java.application_settings_dialog.db12da1a3b"), field(text, "export.filenameTemplate", "%n2 - %t")));
        box.getChildren().add(row(UiText.text("ui.java.application_settings_dialog.2d17e7d295"), field(text, "export.subfolderTemplate", "%a/%s")));
        return scroll(box);
    }

    private Node onlinePane(Map<String, TextField> text, Map<String, PasswordField> secrets, Set<String> unreadableSecrets, Map<String, CheckBox> bool) {
        VBox box = section();
        Label intro = new Label(UiText.text("ui.java.application_settings_dialog.9b64732c86"));
        intro.setWrapText(true);
        box.getChildren().add(intro);
        box.getChildren().add(row(UiText.text("ui.java.application_settings_dialog.50a2b299e6"), field(text, "online.connectTimeoutSeconds", "20")));
        box.getChildren().add(row(UiText.text("ui.java.application_settings_dialog.def88711a5"), field(text, "online.readTimeoutSeconds", "30")));
        box.getChildren().add(checkbox(bool, "online.autoUpdateOnStartup",
                localizationService.text("ui.settings.online.auto_update_on_startup"), false));
        box.getChildren().add(row(localizationService.text("ui.settings.online.user_agent"),
                field(text, "online.userAgent", "MyHomeLib Enterprise/8.0.0")));
        box.getChildren().add(row(UiText.text("ui.java.application_settings_dialog.c925aaed28"), field(text, "online.maxParallelDownloads", "2")));
        box.getChildren().add(row(UiText.text("ui.java.application_settings_dialog.fc423aacf6"), field(text, "online.maxParallelDownloadsPerHost", "2")));
        Button classic = new Button(localizationService.text("ui.settings.online.classic_profile"));
        classic.setTooltip(new Tooltip(localizationService.text("ui.settings.online.classic_profile_note")));
        classic.setOnAction(event -> ClassicOnlineDownloadProfile.values().forEach((key, value) -> {
            TextField input = text.get(key);
            if (input != null) input.setText(value);
        }));
        box.getChildren().add(classic);
        box.getChildren().add(checkbox(bool, "online.archive.highReliabilityValidation",
                UiText.text("ui.java.application_settings_dialog.79e0bebf74"), false));

        box.getChildren().add(new Separator());
        Label proxy = new Label(UiText.text("ui.java.application_settings_dialog.2f57a1f680"));
        proxy.setWrapText(true);
        box.getChildren().add(proxy);
        box.getChildren().add(row(UiText.text("ui.java.application_settings_dialog.6583395323"), field(text, "online.proxy.mode", "SYSTEM")));
        box.getChildren().add(row(UiText.text("ui.java.application_settings_dialog.6e2d40db62"), field(text, "online.proxy.host", "")));
        box.getChildren().add(row(UiText.text("ui.java.application_settings_dialog.94dce6e7f9"), field(text, "online.proxy.port", "8080")));
        box.getChildren().add(row(UiText.text("ui.java.application_settings_dialog.25d23ebc9a"), field(text, "online.proxy.user", "")));
        box.getChildren().add(row(UiText.text("ui.java.application_settings_dialog.a44846bb9e"), secretField(secrets, unreadableSecrets, "online.proxy.password")));

        box.getChildren().add(new Separator());
        Label tls = new Label(UiText.text("ui.java.application_settings_dialog.798dfbd6ad"));
        tls.setWrapText(true);
        box.getChildren().add(tls);
        box.getChildren().add(row(UiText.text("ui.java.application_settings_dialog.bf005d0b23"), field(text, "online.tls.trustStore", "")));
        box.getChildren().add(row(UiText.text("ui.java.application_settings_dialog.8acb7145c6"), field(text, "online.tls.trustStoreType", "PKCS12")));
        box.getChildren().add(row(UiText.text("ui.java.application_settings_dialog.b73fe076d8"), secretField(secrets, unreadableSecrets, "online.tls.trustStorePassword")));
        return scroll(box);
    }


    private final class UpdateSettingsEditor {
        private final ComboBox<SettingOption> channel = new ComboBox<>();
        private final ComboBox<SettingOption> installPolicy = new ComboBox<>();
        private final TextArea status = new TextArea();
        private final VBox pane = section();
        private volatile ReleaseManifest available;

        UpdateSettingsEditor(Map<String, TextField> text, Map<String, CheckBox> bool) {
            channel.getItems().setAll(
                    new SettingOption("STABLE", localizationService.text("ui.settings.updates.channel.stable")),
                    new SettingOption("BETA", localizationService.text("ui.settings.updates.channel.beta")),
                    new SettingOption("MANUAL", localizationService.text("ui.settings.updates.channel.manual")));
            selectOption(channel, settings.get("update.channel", "STABLE"), "STABLE");
            installPolicy.getItems().setAll(
                    new SettingOption("ASK", localizationService.text("ui.settings.updates.policy.ask")),
                    new SettingOption("DOWNLOAD", localizationService.text("ui.settings.updates.policy.download")),
                    new SettingOption("INSTALL_ON_RESTART", localizationService.text("ui.settings.updates.policy.restart")));
            selectOption(installPolicy, settings.get("update.installPolicy", "ASK"), "ASK");

            Label intro = new Label(localizationService.text("ui.settings.updates.info"));
            intro.setWrapText(true);
            pane.getChildren().addAll(intro,
                    row(localizationService.text("ui.settings.updates.channel"), channel),
                    checkbox(bool, "update.autoCheck", localizationService.text("ui.settings.updates.auto_check"), true),
                    row(localizationService.text("ui.settings.updates.policy"), installPolicy),
                    row(localizationService.text("ui.settings.updates.manifest.stable"), field(text, "update.manifest.stable", "")),
                    row(localizationService.text("ui.settings.updates.manifest.beta"), field(text, "update.manifest.beta", "")),
                    row(localizationService.text("ui.settings.updates.manifest.manual"), field(text, "update.manifest.manual", "")),
                    new Separator(),
                    row(localizationService.text("ui.settings.updates.installation_path"), field(text, "update.installationPath", "")),
                    row(localizationService.text("ui.settings.updates.helper_jar"), field(text, "update.helperJar", "")));

            status.setEditable(false);
            status.setWrapText(true);
            status.setPrefRowCount(6);
            status.setPromptText(localizationService.text("ui.settings.updates.release_notes"));

            Button checkNow = new Button(localizationService.text("ui.settings.updates.check_now"));
            checkNow.setId("application-update-check");
            Button stage = new Button(localizationService.text("ui.settings.updates.download_verify"));
            stage.setId("application-update-stage");
            stage.setDisable(true);
            Button restart = new Button(localizationService.text("ui.settings.updates.restart_update"));
            restart.setId("application-update-restart");
            restart.setDisable(true);

            checkNow.setOnAction(e -> {
                checkNow.setDisable(true);
                status.setText(localizationService.text("ui.settings.updates.checking"));
                UpdateChannel selected = UpdateChannel.valueOf(selectedId(channel, "STABLE"));
                backgroundExecutor.submit(() -> applicationUpdateUiService.checkNow(selected))
                        .whenComplete((found, error) -> Platform.runLater(() -> {
                            checkNow.setDisable(false);
                            if (error != null) {
                                available = null; stage.setDisable(true);
                                status.setText(localizationService.text("ui.settings.updates.error") + ": " + UiExceptionSupport.message(error));
                                return;
                            }
                            available = found.orElse(null);
                            if (available == null) {
                                stage.setDisable(true);
                                status.setText(localizationService.text("ui.settings.updates.none"));
                            } else {
                                stage.setDisable(false);
                                status.setText(available.version() + "\n\n" + available.releaseNotes());
                            }
                        }));
            });
            stage.setOnAction(e -> {
                ReleaseManifest candidate = available;
                if (candidate == null) return;
                stage.setDisable(true);
                status.setText(localizationService.text("ui.settings.updates.downloading"));
                backgroundExecutor.submit(() -> applicationUpdateUiService.stage(candidate))
                        .whenComplete((plan, error) -> Platform.runLater(() -> {
                            if (error != null) {
                                stage.setDisable(false);
                                status.setText(localizationService.text("ui.settings.updates.error") + ": " + UiExceptionSupport.message(error));
                                return;
                            }
                            restart.setDisable(!applicationUpdateUiService.canRestartAndApply());
                            status.setText(localizationService.text("ui.settings.updates.staged") + "\n" + plan.manifest().version());
                        }));
            });
            restart.setOnAction(e -> {
                try { applicationUpdateUiService.restartAndApply(); }
                catch (Exception failure) {
                    status.setText(localizationService.text("ui.settings.updates.error") + ": " + UiExceptionSupport.message(failure));
                }
            });
            try {
                restart.setDisable(applicationUpdateUiService.pendingPlan().isEmpty() || !applicationUpdateUiService.canRestartAndApply());
            } catch (Exception ignored) { restart.setDisable(true); }

            pane.getChildren().addAll(new HBox(8, checkNow, stage, restart), status);
        }

        Node pane() { return scroll(pane); }
        void save() {
            settings.put("update.channel", selectedId(channel, "STABLE"));
            settings.put("update.installPolicy", selectedId(installPolicy, "ASK"));
        }
        private void selectOption(ComboBox<SettingOption> combo, String id, String fallback) {
            combo.getItems().stream().filter(v -> v.id().equalsIgnoreCase(id)).findFirst()
                    .or(() -> combo.getItems().stream().filter(v -> v.id().equalsIgnoreCase(fallback)).findFirst())
                    .ifPresent(combo::setValue);
        }
        private String selectedId(ComboBox<SettingOption> combo, String fallback) {
            return combo.getValue() == null ? fallback : combo.getValue().id();
        }
    }

    private final class ThemeEditor {
        private final ComboBox<ApplicationThemeService.ThemeMode> mode = new ComboBox<>();
        private final ColorPicker background = new ColorPicker();
        private final ColorPicker panel = new ColorPicker();
        private final ColorPicker text = new ColorPicker();
        private final ColorPicker accent = new ColorPicker();
        private final ColorPicker seriesRow = new ColorPicker();
        private final ColorPicker bookRow = new ColorPicker();
        private final ColorPicker downloadedRow = new ColorPicker();
        private final Spinner<Double> fontSize = new Spinner<>(9.0, 24.0, 13.0, 0.5);
        private final VBox pane = section();
        private boolean updating;

        ThemeEditor(ApplicationThemeService.ThemeConfig initial) {
            mode.getItems().setAll(ApplicationThemeService.ThemeMode.values());
            mode.setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(ApplicationThemeService.ThemeMode value) {
                    if (value == null) return "";
                    return switch (value) {
                        case SYSTEM -> UiText.text("ui.java.application_settings_dialog.23feef477f"); case LIGHT -> UiText.text("ui.reader.theme.light"); case DARK -> UiText.text("ui.reader.theme.dark");
                        case AMOLED -> UiText.text("ui.settings.theme.amoled"); case CUSTOM -> UiText.text("ui.java.application_settings_dialog.4cab94b927");
                    };
                }
                @Override public ApplicationThemeService.ThemeMode fromString(String value) { return mode.getValue(); }
            });
            applyToControls(initial);

            Label hint = new Label(UiText.text("ui.java.application_settings_dialog.ee31d2987d"));
            hint.setWrapText(true);
            pane.getChildren().addAll(
                    row(UiText.text("ui.fxml.smart_collection.mode"), mode),
                    row(UiText.text("ui.java.application_settings_dialog.05ae1df09a"), background),
                    row(UiText.text("ui.java.application_settings_dialog.3882348771"), panel),
                    row(UiText.text("ui.java.application_settings_dialog.93970437e2"), text),
                    row(UiText.text("ui.java.application_settings_dialog.492e56a1ba"), accent),
                    row(UiText.text("ui.java.application_settings_dialog.72d0420036"), seriesRow),
                    row(UiText.text("ui.java.application_settings_dialog.7444c09709"), bookRow),
                    row(UiText.text("ui.java.application_settings_dialog.a314d92445"), downloadedRow),
                    row(UiText.text("ui.java.application_settings_dialog.58be1e4f52"), fontSize), hint);
            Button reset = new Button(UiText.text("ui.java.application_settings_dialog.82ed5ad092"));
            reset.setOnAction(e -> {
                applyToControls(themeService.customDefaults());
                mode.setValue(ApplicationThemeService.ThemeMode.CUSTOM);
                preview();
            });
            pane.getChildren().add(reset);

            javafx.beans.value.ChangeListener<Object> listener = (obs, oldValue, newValue) -> preview();
            mode.valueProperty().addListener(listener);
            background.valueProperty().addListener(listener); panel.valueProperty().addListener(listener);
            text.valueProperty().addListener(listener); accent.valueProperty().addListener(listener);
            seriesRow.valueProperty().addListener(listener); bookRow.valueProperty().addListener(listener);
            downloadedRow.valueProperty().addListener(listener); fontSize.valueProperty().addListener(listener);
            updateCustomControls();
        }

        Node pane() { return scroll(pane); }

        ApplicationThemeService.ThemeConfig config() {
            return new ApplicationThemeService.ThemeConfig(mode.getValue(), hex(background.getValue()), hex(panel.getValue()),
                    hex(text.getValue()), hex(accent.getValue()), hex(seriesRow.getValue()), hex(bookRow.getValue()),
                    hex(downloadedRow.getValue()), fontSize.getValue());
        }

        private void preview() {
            if (updating) return;
            updateCustomControls();
            themeService.apply(config());
        }

        private void updateCustomControls() {
            boolean custom = mode.getValue() == ApplicationThemeService.ThemeMode.CUSTOM;
            background.setDisable(!custom); panel.setDisable(!custom); text.setDisable(!custom); accent.setDisable(!custom);
            seriesRow.setDisable(!custom); bookRow.setDisable(!custom); downloadedRow.setDisable(!custom);
        }

        private void applyToControls(ApplicationThemeService.ThemeConfig value) {
            updating = true;
            try {
                mode.setValue(value.mode());
                background.setValue(Color.web(value.background())); panel.setValue(Color.web(value.panel()));
                text.setValue(Color.web(value.text())); accent.setValue(Color.web(value.accent()));
                seriesRow.setValue(Color.web(value.seriesRow())); bookRow.setValue(Color.web(value.bookRow()));
                downloadedRow.setValue(Color.web(value.downloadedRow()));
                fontSize.getValueFactory().setValue(value.fontSize());
            } finally { updating = false; }
            updateCustomControls();
        }

        private String hex(Color color) {
            Color c = color == null ? Color.BLACK : color;
            return String.format(java.util.Locale.ROOT, "#%02x%02x%02x",
                    Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
        }
    }

    private void addConverterRow(VBox box, Map<String, TextField> text, String label, String key, String ext) {
        TextField command = field(text, key, "");
        box.getChildren().add(commandRow(label, command, Map.of(
                "%SRC%", samplePath("source.fb2"), "%DST%", samplePath("converted." + ext),
                "%TITLE%", UiText.text("ui.java.application_settings_dialog.6ed732b2ca"), "%BOOKID%", "1")));
    }

    private Node commandRow(String label, TextField field, Map<String, String> placeholders) {
        Button test = new Button(UiText.text("ui.java.application_settings_dialog.ec1868c5a7"));
        test.setOnAction(e -> testCommand(test, field.getText(), placeholders));
        HBox controls = new HBox(8, field, test);
        javafx.scene.layout.HBox.setHgrow(field, javafx.scene.layout.Priority.ALWAYS);
        return row(label, controls);
    }


    private void showLanguageDiagnostics() {
        var messages = localizationService.languageDiagnostics();
        long errors = messages.stream().filter(line -> line.startsWith("ERROR")).count();
        long warnings = messages.stream().filter(line -> line.startsWith("WARN")).count();
        String summary = errors == 0 && warnings == 0
                ? UiText.text("ui.settings.language_diagnostics.ok")
                : UiText.format("ui.settings.language_diagnostics.summary", errors, warnings);
        String content = summary + "\n" + UiText.text("ui.settings.language_diagnostics.details_in_file");
        alert(errors > 0 ? Alert.AlertType.WARNING : Alert.AlertType.INFORMATION,
                UiText.text("ui.java.application_settings_dialog.0eec5f4c8e"), content);
    }

    private void createSupportBundle(Window owner) {
        CheckBox logs = new CheckBox(UiText.text("ui.java.application_settings_dialog.8b590da037"));
        CheckBox threads = new CheckBox(UiText.text("ui.java.application_settings_dialog.fbbda3770b"));
        CheckBox releaseDocs = new CheckBox(UiText.text("ui.java.application_settings_dialog.867e140a1c"));
        logs.setSelected(true);
        threads.setSelected(true);
        releaseDocs.setSelected(true);

        TextArea preview = new TextArea();
        preview.setEditable(false);
        preview.setWrapText(false);
        preview.setPrefSize(720, 300);
        Runnable refresh = () -> preview.setText(supportBundleService.preview(new SupportBundleOptions(
                logs.isSelected(), threads.isSelected(), releaseDocs.isSelected())).displayText());
        logs.selectedProperty().addListener((obs, oldValue, newValue) -> refresh.run());
        threads.selectedProperty().addListener((obs, oldValue, newValue) -> refresh.run());
        releaseDocs.selectedProperty().addListener((obs, oldValue, newValue) -> refresh.run());
        refresh.run();

        Dialog<ButtonType> contents = new Dialog<>();
        DialogLocalization.prepare(contents);
        contents.setTitle(UiText.text("ui.java.application_settings_dialog.474fb5031d"));
        contents.setHeaderText(UiText.text("ui.java.application_settings_dialog.ca5d09a9e7"));
        if (owner != null) contents.initOwner(owner);
        contents.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        contents.getDialogPane().setContent(new VBox(8, logs, threads, releaseDocs, preview));
        if (contents.showAndWait().filter(ButtonType.OK::equals).isEmpty()) return;

        SupportBundleOptions options = new SupportBundleOptions(
                logs.isSelected(), threads.isSelected(), releaseDocs.isSelected());
        var selected = fileChooserService.chooseFileToSave(owner,
                UiText.text("ui.java.application_settings_dialog.02a10cbb94"),
                "MyHomeLib-support-" + java.time.LocalDate.now() + ".zip",
                java.util.List.of(new FileChooser.ExtensionFilter(UiText.text("ui.java.application_settings_dialog.c6fd5ab5c0"), "*.zip")));
        if (selected == null) return;
        backgroundExecutor.submit(() -> supportBundleService.create(selected.toPath(), options))
                .whenComplete((output, error) -> UiExecutor.runOnUiThread(() -> {
                    if (error != null) {
                        alert(Alert.AlertType.ERROR, UiText.text("ui.java.application_settings_dialog.68514b318f"),
                                UiText.text("ui.java.application_settings_dialog.1f93cb035e") + rootMessage(error));
                    } else {
                        alert(Alert.AlertType.INFORMATION, UiText.text("ui.java.application_settings_dialog.68514b318f"), UiText.text("ui.java.application_settings_dialog.c7f7d042da") + output);
                    }
                }));
    }

    private void testCommand(Button trigger, String template, Map<String, String> placeholders) {
        if (template == null || template.isBlank()) {
            alert(Alert.AlertType.INFORMATION, UiText.text("ui.java.application_settings_dialog.0733cbf4e0"), UiText.text("ui.java.application_settings_dialog.66a508ecfe"));
            return;
        }
        Map<String, String> safePlaceholders = Map.copyOf(placeholders);
        trigger.setDisable(true);
        backgroundExecutor.submit(() -> executeCommandTest(template, safePlaceholders))
                .whenComplete((result, error) -> UiExecutor.runOnUiThread(() -> {
                    trigger.setDisable(false);
                    if (error != null) {
                        alert(Alert.AlertType.ERROR, UiText.text("ui.java.application_settings_dialog.0733cbf4e0"), rootMessage(error));
                    } else {
                        alert(result.type(), UiText.text("ui.java.application_settings_dialog.0733cbf4e0"), result.details());
                    }
                }));
    }

    private CommandTestResult executeCommandTest(String template, Map<String, String> placeholders) throws Exception {
        var args = CommandTemplate.expand(template, placeholders);
        if (args.isEmpty()) throw new IllegalArgumentException(UiText.text("ui.java.application_settings_dialog.a408a3b06f"));
        int timeout = Math.max(1, Math.min(60, parseInt(settings.get("converter.testTimeoutSeconds", "10"), 10)));
        final int maxCaptureBytes = 64 * 1024;
        try {
            var safeArgs = new java.util.ArrayList<>(args);
            safeArgs.set(0, ExecutablePathSafety.resolve(
                    safeArgs.getFirst(), "Converter test executable").toString());
            ProcessExecutionSupport.Result process = ProcessExecutionSupport.run(
                    safeArgs, null, java.time.Duration.ofSeconds(timeout), maxCaptureBytes);
            String details = UiText.text("ui.java.application_settings_dialog.b67ad236d6") + process.exitCode() + UiText.text("ui.java.application_settings_dialog.755cfb7f1e") + String.join(" | ", safeArgs)
                    + "\n\n" + commandOutput(process);
            return new CommandTestResult(process.exitCode() == 0 ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                    details);
        } catch (ProcessExecutionSupport.ProcessTimeoutException timeoutError) {
            return new CommandTestResult(Alert.AlertType.WARNING, UiText.text("ui.java.application_settings_dialog.9dda908164") + timeout + UiText.text("ui.java.application_settings_dialog.d0b39ecacf"));
        }
    }

    private static String commandOutput(ProcessExecutionSupport.Result process) {
        String stdout = process.stdoutText();
        String stderr = process.stderrText();
        StringBuilder text = new StringBuilder();
        if (!stdout.isBlank()) text.append(stdout.stripTrailing());
        if (!stderr.isBlank()) {
            if (!text.isEmpty()) text.append(UiText.text("ui.java.application_settings_dialog.f1fc3249ad"));
            text.append(stderr.stripTrailing());
        }
        if (process.stdoutTruncated() || process.stderrTruncated()) text.append(UiText.text("ui.java.application_settings_dialog.096369e7a3"));
        return text.isEmpty() ? UiText.text("ui.java.application_settings_dialog.e9c1b3c61c") : text.toString();
    }

    private static String rootMessage(Throwable error) {
        return UiExceptionSupport.message(error);
    }

    private record CommandTestResult(Alert.AlertType type, String details) { }

    private void alert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        DialogLocalization.prepare(alert); alert.setTitle(title); alert.setHeaderText(null);
        TextArea area = new TextArea(content == null ? "" : content); area.setEditable(false); area.setWrapText(true);
        area.setPrefSize(720, 320); alert.getDialogPane().setContent(area); alert.showAndWait();
    }

    private static final class IndexingSettingsEditor {
        private final ComboBox<IndexingResourceProfile> profile = new ComboBox<>();
        private final CheckBox pauseOnBattery = new CheckBox(UiText.text("ui.java.application_settings_dialog.44f77e331c"));
        private final Label summary = new Label();

        IndexingSettingsEditor(IndexingPerformanceSettings current) {
            profile.getItems().setAll(IndexingResourceProfile.values());
            profile.setCellFactory(list -> new ListCell<>() {
                @Override protected void updateItem(IndexingResourceProfile item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : resourceProfileText(item));
                }
            });
            profile.setButtonCell(new ListCell<>() {
                @Override protected void updateItem(IndexingResourceProfile item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : resourceProfileText(item));
                }
            });
            profile.setValue(current.profile());
            profile.setAccessibleText(UiText.text("ui.java.application_settings_dialog.fa3104c577"));
            pauseOnBattery.setSelected(current.pauseOnBattery());
            pauseOnBattery.setAccessibleText(UiText.text("ui.java.application_settings_dialog.44f77e331c"));
            profile.valueProperty().addListener((obs, oldValue, newValue) -> updateSummary());
            updateSummary();
        }

        Node pane() {
            VBox box = new VBox(10);
            box.setPadding(new Insets(12));
            Label explanation = new Label(UiText.text("ui.java.application_settings_dialog.2c555301e4"));
            explanation.setWrapText(true);
            summary.setWrapText(true);
            GridPane row = new GridPane();
            row.setHgap(10);
            Label label = new Label(UiText.text("ui.java.application_settings_dialog.6bc20e14f6"));
            label.setMinWidth(170);
            row.add(label, 0, 0);
            row.add(profile, 1, 0);
            box.getChildren().addAll(explanation, row, pauseOnBattery, summary);
            return new ScrollPane(box);
        }

        IndexingPerformanceSettings value() {
            IndexingResourceProfile selected = profile.getValue() == null ? IndexingResourceProfile.BALANCED : profile.getValue();
            return new IndexingPerformanceSettings(selected, pauseOnBattery.isSelected());
        }

        private static String resourceProfileText(IndexingResourceProfile profile) {
            return switch (profile) {
                case ECO -> UiText.text("ui.java.application_settings_dialog.898f1651a0");
                case BALANCED -> UiText.text("ui.java.application_settings_dialog.b397f40f09");
                case FAST -> UiText.text("ui.java.application_settings_dialog.af153c00c6");
            };
        }

        private void updateSummary() {
            IndexingPerformanceSettings value = value();
            int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
            long io = value.ioBytesPerSecond();
            String ioLabel = io <= 0 ? UiText.text("ui.java.application_settings_dialog.9639263134") : (io / 1024 / 1024) + UiText.text("ui.java.application_settings_dialog.a73d82759c");
            summary.setText(UiText.text("ui.java.application_settings_dialog.c89dab149f") + value.workerThreads(processors) + UiText.text("ui.java.application_settings_dialog.94dd83f63a") + ioLabel + ".");
        }
    }

    private record LanguageOption(String code, String name) {
        @Override public String toString() { return name; }
    }

    private String samplePath(String name) { return AppPaths.cacheDir().resolve("command test").resolve(name).toAbsolutePath().toString(); }
    private int parseInt(String value, int fallback) { try { return Integer.parseInt(value); } catch (Exception e) { return fallback; } }

    private TextField field(Map<String, TextField> map, String key, String def) {
        TextField f = new TextField(settings.get(key, def));
        f.setPrefColumnCount(42);
        map.put(key, f);
        return f;
    }

    private PasswordField secretField(Map<String, PasswordField> map, Set<String> unreadableSecrets, String key) {
        PasswordField field = new PasswordField();
        String stored = settings.get(key, "");
        if (stored != null && !stored.isBlank() && EncryptionUtil.isEncrypted(stored)) {
            try { field.setText(EncryptionUtil.decrypt(stored)); }
            catch (RuntimeException decryptError) {
                unreadableSecrets.add(key);
                field.clear();
                field.setDisable(true);
                field.setPromptText(UiText.text("ui.java.application_settings_dialog.5bb8de6b59"));
                field.setTooltip(new Tooltip(UiText.text("ui.java.application_settings_dialog.9a26ee38e9")));
            }
        }
        field.setPrefColumnCount(42);
        map.put(key, field);
        return field;
    }

    private CheckBox checkbox(Map<String, CheckBox> map, String key, String label, boolean def) {
        CheckBox c = new CheckBox(label);
        c.setSelected(settings.getBoolean(key, def));
        map.put(key, c);
        return c;
    }

    private Node row(String label, Node control) {
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(6);
        Label l = new Label(label); l.setMinWidth(170);
        GridPane.setHgrow(control, javafx.scene.layout.Priority.ALWAYS);
        grid.add(l,0,0); grid.add(control,1,0);
        return grid;
    }

    private VBox section() { VBox box = new VBox(9); box.setPadding(new Insets(12)); return box; }
    private ScrollPane scroll(Node n) { ScrollPane s = new ScrollPane(n); s.setFitToWidth(true); return s; }
}