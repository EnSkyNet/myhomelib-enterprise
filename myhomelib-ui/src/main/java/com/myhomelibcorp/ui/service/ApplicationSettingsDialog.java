package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.util.CommandTemplate;
import com.myhomelibcorp.shared.util.AppPaths;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Central settings dialog. Values are stored through ApplicationSettingsPort, so
 * portable mode writes them next to the application instead of the user profile.
 */
@Component
public class ApplicationSettingsDialog {
    private final ApplicationSettingsPort settings;
    private final SupportBundleService supportBundleService;

    public ApplicationSettingsDialog(ApplicationSettingsPort settings, SupportBundleService supportBundleService) {
        this.settings = settings;
        this.supportBundleService = supportBundleService;
    }

    public void show(Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Налаштування MyHomeLib");
        dialog.setHeaderText("Загальні, Reader, зовнішні програми, конвертери та пристрій");
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Map<String, TextField> text = new LinkedHashMap<>();
        Map<String, CheckBox> bool = new LinkedHashMap<>();

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(tab("Загальні", generalPane(text, bool)));
        tabs.getTabs().add(tab("Зовнішнє читання", externalReadersPane(text)));
        tabs.getTabs().add(tab("Конвертери", convertersPane(text)));
        tabs.getTabs().add(tab("Пристрій / експорт", devicePane(text, bool)));
        tabs.getTabs().add(tab("Online", onlinePane(text)));
        tabs.setPrefSize(760, 560);
        dialog.getDialogPane().setContent(tabs);

        dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(ok -> {
            text.forEach((key, field) -> settings.put(key, field.getText() == null ? "" : field.getText().trim()));
            bool.forEach((key, check) -> settings.putBoolean(key, check.isSelected()));
        });
    }

    private Tab tab(String title, Node content) { return new Tab(title, content); }

    private Node generalPane(Map<String, TextField> text, Map<String, CheckBox> bool) {
        VBox box = section();
        ComboBox<String> lang = new ComboBox<>();
        lang.getItems().addAll("uk", "en", "bg");
        lang.setValue(settings.get("ui.language", "uk"));
        lang.valueProperty().addListener((o,a,b) -> settings.put("ui.language", b));
        CheckBox confirmDelete = checkbox(bool, "ui.confirmDelete", "Підтверджувати видалення книг", true);
        CheckBox restoreSession = checkbox(bool, "ui.restoreSession", "Відновлювати останню сесію", true);
        CheckBox autoIndex = checkbox(bool, "search.autoIndex", "Оновлювати пошуковий індекс після змін", true);
        Label paths = new Label("Каталог даних: " + AppPaths.dataDir() + "\nPortable mode: " + (AppPaths.portableMode() ? "увімкнено" : "вимкнено"));
        paths.setWrapText(true);
        Button diagnostics = new Button("Створити діагностичний ZIP...");
        diagnostics.setOnAction(e -> createSupportBundle(diagnostics.getScene() == null ? null : diagnostics.getScene().getWindow()));
        box.getChildren().addAll(row("Мова інтерфейсу", lang), confirmDelete, restoreSession, autoIndex, new Separator(), paths, diagnostics);
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

    private Node devicePane(Map<String, TextField> text, Map<String, CheckBox> bool) {
        VBox box = section();
        box.getChildren().add(new Label("Шаблони: %t=назва, %a=автор, %s=серія, %n=№ серії, %id=ID.\nPost-command: %DEST%, %TMP%, %FILE%, %DESTFILE%, %FILENAME%, %TITLE%, %AUTHOR%, %SERIES%, %EXT%, %BOOKID%."));
        box.getChildren().add(row("Шаблон імені", field(text, "export.filenameTemplate", "%a - %t")));
        box.getChildren().add(row("Підпапка", field(text, "export.subfolderTemplate", "")));
        TextField post = field(text, "export.postCommand", "");
        box.getChildren().add(commandRow("Команда після відправки", post, Map.ofEntries(
                Map.entry("%DEST%", samplePath("device")), Map.entry("%TMP%", samplePath("tmp")),
                Map.entry("%FILE%", samplePath("source.fb2")), Map.entry("%DESTFILE%", samplePath("device/book.epub")),
                Map.entry("%FILENAME%", "book.epub"), Map.entry("%TITLE%", "Тестова книга"),
                Map.entry("%AUTHOR%", "Test Author"), Map.entry("%SERIES%", "Test Series"),
                Map.entry("%EXT%", "epub"), Map.entry("%BOOKID%", "1"))));
        box.getChildren().add(checkbox(bool, "export.runPostCommand", "Виконувати post-command після кожної книги", false));
        return scroll(box);
    }

    private Node onlinePane(Map<String, TextField> text) {
        VBox box = section();
        box.getChildren().add(new Label("Для online-колекцій URL задається у властивостях колекції. Тут — глобальні параметри HTTP."));
        box.getChildren().add(row("Timeout connect, сек", field(text, "online.connectTimeoutSeconds", "20")));
        box.getChildren().add(row("Timeout read, сек", field(text, "online.readTimeoutSeconds", "120")));
        box.getChildren().add(row("User-Agent", field(text, "online.userAgent", "MyHomeLib/1.0.0")));
        box.getChildren().add(row("Макс. паралельних завантажень", field(text, "online.maxParallelDownloads", "2")));
        return scroll(box);
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
        byte[] all = Files.readAllBytes(output);
        int max = Math.min(all.length, 64 * 1024);
        String text = new String(all, 0, max, StandardCharsets.UTF_8);
        if (all.length > max) text += "\n… output truncated …";
        return text.isBlank() ? "(stdout/stderr порожній)" : text;
    }

    private void alert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type); alert.setTitle(title); alert.setHeaderText(null);
        TextArea area = new TextArea(content == null ? "" : content); area.setEditable(false); area.setWrapText(true);
        area.setPrefSize(720, 320); alert.getDialogPane().setContent(area); alert.showAndWait();
    }

    private String samplePath(String name) { return AppPaths.cacheDir().resolve("command test").resolve(name).toAbsolutePath().toString(); }
    private int parseInt(String value, int fallback) { try { return Integer.parseInt(value); } catch (Exception e) { return fallback; } }

    private TextField field(Map<String, TextField> map, String key, String def) {
        TextField f = new TextField(settings.get(key, def));
        f.setPrefColumnCount(42);
        map.put(key, f);
        return f;
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
