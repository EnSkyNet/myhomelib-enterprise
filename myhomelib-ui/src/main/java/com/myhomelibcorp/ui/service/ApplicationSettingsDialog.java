package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.util.CommandTemplate;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.BoundedIoSupport;
import com.myhomelibcorp.shared.util.EncryptionUtil;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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

    public ApplicationSettingsDialog(ApplicationSettingsPort settings,
                                     SupportBundleService supportBundleService,
                                     LocalizationService localizationService,
                                     ApplicationThemeService themeService) {
        this.settings = settings;
        this.supportBundleService = supportBundleService;
        this.localizationService = localizationService;
        this.themeService = themeService;
    }

    public void show(Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Налаштування MyHomeLib");
        dialog.setHeaderText("Загальні, Reader, зовнішні програми, конвертери та пристрій");
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Map<String, TextField> text = new LinkedHashMap<>();
        Map<String, PasswordField> secrets = new LinkedHashMap<>();
        Set<String> unreadableSecrets = new HashSet<>();
        Map<String, CheckBox> bool = new LinkedHashMap<>();

        ApplicationThemeService.ThemeConfig originalTheme = themeService.current();
        ThemeEditor themeEditor = new ThemeEditor(originalTheme);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(tab("Загальні", generalPane(bool)));
        tabs.getTabs().add(tab("Тема", themeEditor.pane()));
        tabs.getTabs().add(tab("Зовнішнє читання", externalReadersPane(text)));
        tabs.getTabs().add(tab("Конвертери", convertersPane(text)));
        tabs.getTabs().add(tab("Пристрій / експорт", devicePane(text)));
        tabs.getTabs().add(tab("Online", onlinePane(text, secrets, unreadableSecrets, bool)));
        tabs.setPrefSize(790, 590);
        dialog.getDialogPane().setContent(tabs);

        boolean accepted = dialog.showAndWait().filter(ButtonType.OK::equals).isPresent();
        if (!accepted) {
            themeService.apply(originalTheme);
            secrets.values().forEach(PasswordField::clear);
            return;
        }

        text.forEach((key, field) -> settings.put(key, field.getText() == null ? "" : field.getText().trim()));
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
        CheckBox confirmDelete = checkbox(bool, "ui.confirmDelete", "Підтверджувати видалення книг", true);
        CheckBox restoreSession = checkbox(bool, "ui.restoreSession", "Відновлювати останню сесію", true);
        Label paths = new Label("Каталог даних: " + AppPaths.dataDir() + "\nPortable mode: " + (AppPaths.portableMode() ? "увімкнено" : "вимкнено"));
        paths.setWrapText(true);
        Button languageDiagnostics = new Button("Діагностика мов...");
        languageDiagnostics.setOnAction(e -> showLanguageDiagnostics());
        Button diagnostics = new Button("Створити діагностичний ZIP...");
        diagnostics.setOnAction(e -> createSupportBundle(diagnostics.getScene() == null ? null : diagnostics.getScene().getWindow()));
        HBox diagnosticActions = new HBox(8, languageDiagnostics, diagnostics);
        box.getChildren().addAll(row("Мова інтерфейсу", lang), confirmDelete, restoreSession, new Separator(), paths, diagnosticActions);
        return scroll(box);
    }

    private Node externalReadersPane(Map<String, TextField> text) {
        VBox box = section();
        box.getChildren().add(new Label("Команда може містити %FILE%, %TITLE%, %AUTHOR%. Порожнє поле = системна програма."));
        for (String ext : new String[]{"fb2","fbd","epub","txt","pdf","mobi","azw","azw3","djvu","doc","docx","rtf","html","htm"}) {
            TextField command = field(text, "reader.external." + ext, "");
            box.getChildren().add(commandRow(ext.toUpperCase(), command, Map.of(
                    "%FILE%", samplePath("book." + ext), "%TITLE%", "Тестова книга", "%AUTHOR%", "Test Author")));
        }
        return scroll(box);
    }

    private Node convertersPane(Map<String, TextField> text) {
        VBox box = section();
        box.getChildren().add(new Label("Шаблони команд: %SRC% — джерело, %DST% — результат, %TITLE%, %BOOKID%."));
        addConverterRow(box, text, "FB2 → EPUB", "converter.epub.command", "epub");
        addConverterRow(box, text, "FB2 → PDF", "converter.pdf.command", "pdf");
        addConverterRow(box, text, "FB2 → MOBI", "converter.mobi.command", "mobi");
        addConverterRow(box, text, "FB2 → LRF", "converter.lrf.command", "lrf");
        box.getChildren().add(row("Timeout, секунд", field(text, "converter.timeoutSeconds", "300")));
        return scroll(box);
    }

    private Node devicePane(Map<String, TextField> text) {
        VBox box = section();
        box.getChildren().add(new Label("Шаблони: %t=назва, %a=автор, %s=серія, %n=№ серії, %id=ID. Дії після експорту налаштовуються у профілі експорту/дій."));
        box.getChildren().add(row("Шаблон імені", field(text, "export.filenameTemplate", "%n2 - %t")));
        box.getChildren().add(row("Підпапка", field(text, "export.subfolderTemplate", "%a/%s")));
        return scroll(box);
    }

    private Node onlinePane(Map<String, TextField> text, Map<String, PasswordField> secrets, Set<String> unreadableSecrets, Map<String, CheckBox> bool) {
        VBox box = section();
        Label intro = new Label("Для online-колекцій URL задається у властивостях колекції. TLS за замовчуванням використовує стандартну перевірку JVM; режим trust-all відсутній.");
        intro.setWrapText(true);
        box.getChildren().add(intro);
        box.getChildren().add(row("Timeout connect, сек", field(text, "online.connectTimeoutSeconds", "20")));
        box.getChildren().add(row("Timeout read, сек", field(text, "online.readTimeoutSeconds", "120")));
        box.getChildren().add(row("User-Agent", field(text, "online.userAgent", "MyHomeLib Enterprise/7.1")));
        box.getChildren().add(row("Макс. паралельних завантажень", field(text, "online.maxParallelDownloads", "2")));
        box.getChildren().add(row("Макс. паралельних на один хост", field(text, "online.maxParallelDownloadsPerHost", "2")));
        box.getChildren().add(checkbox(bool, "online.archive.highReliabilityValidation",
                "Повна CRC/size перевірка ZIP після завантаження (повільніше)", false));

        box.getChildren().add(new Separator());
        Label proxy = new Label("Proxy: SYSTEM, NONE або HTTP. Для SOCKS використовуйте system proxy JVM/OS.");
        proxy.setWrapText(true);
        box.getChildren().add(proxy);
        box.getChildren().add(row("Proxy mode", field(text, "online.proxy.mode", "SYSTEM")));
        box.getChildren().add(row("Proxy host", field(text, "online.proxy.host", "")));
        box.getChildren().add(row("Proxy port", field(text, "online.proxy.port", "8080")));
        box.getChildren().add(row("Proxy user", field(text, "online.proxy.user", "")));
        box.getChildren().add(row("Proxy password", secretField(secrets, unreadableSecrets, "online.proxy.password")));

        box.getChildren().add(new Separator());
        Label tls = new Label("Custom CA: вкажіть JKS/PKCS12 trust store. Порожній шлях = стандартне JVM trust store.");
        tls.setWrapText(true);
        box.getChildren().add(tls);
        box.getChildren().add(row("TLS trust store", field(text, "online.tls.trustStore", "")));
        box.getChildren().add(row("Trust store type", field(text, "online.tls.trustStoreType", "PKCS12")));
        box.getChildren().add(row("Trust store password", secretField(secrets, unreadableSecrets, "online.tls.trustStorePassword")));
        return scroll(box);
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
                        case SYSTEM -> "Системна"; case LIGHT -> "Світла"; case DARK -> "Темна"; case CUSTOM -> "Власна";
                    };
                }
                @Override public ApplicationThemeService.ThemeMode fromString(String value) { return mode.getValue(); }
            });
            applyToControls(initial);

            Label hint = new Label("Зміни застосовуються одразу для всіх відкритих вікон. Reader має власну незалежну тему читання.");
            hint.setWrapText(true);
            pane.getChildren().addAll(
                    row("Режим", mode),
                    row("Фон програми", background),
                    row("Панелі", panel),
                    row("Текст", text),
                    row("Accent", accent),
                    row("Рядок серії", seriesRow),
                    row("Рядок книги", bookRow),
                    row("Завантажена книга", downloadedRow),
                    row("Розмір шрифту", fontSize), hint);
            Button reset = new Button("Відновити стандартні");
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
                "%TITLE%", "Тестова книга", "%BOOKID%", "1")));
    }

    private Node commandRow(String label, TextField field, Map<String, String> placeholders) {
        Button test = new Button("Тест");
        test.setOnAction(e -> testCommand(field.getText(), placeholders));
        HBox controls = new HBox(8, field, test);
        javafx.scene.layout.HBox.setHgrow(field, javafx.scene.layout.Priority.ALWAYS);
        return row(label, controls);
    }


    private void showLanguageDiagnostics() {
        var messages = localizationService.languageDiagnostics();
        String content = "Файл: " + localizationService.languageDiagnosticsFile() + "\n\n"
                + String.join("\n", messages);
        alert(messages.stream().anyMatch(line -> line.startsWith("ERROR"))
                        ? Alert.AlertType.WARNING : Alert.AlertType.INFORMATION,
                "Діагностика мовних каталогів", content);
    }

    private void createSupportBundle(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Зберегти діагностичний ZIP");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP archive", "*.zip"));
        chooser.setInitialFileName("MyHomeLib-support-" + java.time.LocalDate.now() + ".zip");
        var selected = chooser.showSaveDialog(owner);
        if (selected == null) return;
        try {
            Path output = supportBundleService.create(selected.toPath());
            alert(Alert.AlertType.INFORMATION, "Діагностика", "Створено:\n" + output);
        } catch (Exception ex) {
            alert(Alert.AlertType.ERROR, "Діагностика", "Не вдалося створити ZIP: " + ex.getMessage());
        }
    }
    private void testCommand(String template, Map<String, String> placeholders) {
        if (template == null || template.isBlank()) {
            alert(Alert.AlertType.INFORMATION, "Тест команди", "Команда не задана.");
            return;
        }
        Path output = null;
        try {
            var args = CommandTemplate.expand(template, placeholders);
            if (args.isEmpty()) throw new IllegalArgumentException("Команда порожня після розбору");
            output = Files.createTempFile("myhomelib-command-test-", ".log");
            Process process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            int timeout = Math.max(1, Math.min(60, parseInt(settings.get("converter.testTimeoutSeconds", "10"), 10)));
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                alert(Alert.AlertType.WARNING, "Тест команди", "Timeout після " + timeout + " с.\n" + preview(output));
                return;
            }
            String details = "Exit code: " + process.exitValue() + "\nКоманда: " + String.join(" | ", args) + "\n\n" + preview(output);
            alert(process.exitValue() == 0 ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR, "Тест команди", details);
        } catch (Exception ex) {
            alert(Alert.AlertType.ERROR, "Тест команди", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            if (output != null) try { Files.deleteIfExists(output); } catch (IOException ignored) { }
        }
    }

    private String preview(Path output) throws IOException {
        if (output == null || !Files.exists(output)) return "(stdout/stderr порожній)";
        final int maxBytes = 64 * 1024;
        byte[] prefix;
        try (var in = Files.newInputStream(output)) {
            prefix = BoundedIoSupport.readPrefix(in, maxBytes + 1);
        }
        int visible = Math.min(prefix.length, maxBytes);
        String text = new String(prefix, 0, visible, StandardCharsets.UTF_8);
        if (prefix.length > maxBytes || Files.size(output) > maxBytes) text += "\n… output truncated …";
        return text.isBlank() ? "(stdout/stderr порожній)" : text;
    }

    private void alert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type); alert.setTitle(title); alert.setHeaderText(null);
        TextArea area = new TextArea(content == null ? "" : content); area.setEditable(false); area.setWrapText(true);
        area.setPrefSize(720, 320); alert.getDialogPane().setContent(area); alert.showAndWait();
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
                field.setPromptText("Існуюче зашифроване значення буде збережено");
                field.setTooltip(new Tooltip("Не вдалося розшифрувати поточне значення. Натискання OK не очистить його."));
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
