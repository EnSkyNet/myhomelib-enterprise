package com.myhomelibcorp.ui.integration;

import com.myhomelibcorp.application.sync.conflict.SyncConflictReviewSelection;
import com.myhomelibcorp.application.extension.LocalMetadataExtractionService;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.sync.SyncConflictReviewDialog;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** User-facing management center for cross-device sync, external plugins and AI providers. */
@Component
public final class IntegrationCenterDialog {
    private final IntegrationBackend backend;
    private final UiBackgroundExecutor background;
    private final DialogService dialogs;
    private final LocalizationService i18n;
    private final LocalMetadataExtractionService localMetadata;

    public IntegrationCenterDialog(IntegrationBackend backend,
                                   UiBackgroundExecutor background,
                                   DialogService dialogs,
                                   LocalizationService i18n,
                                   LocalMetadataExtractionService localMetadata) {
        this.backend = backend;
        this.background = background;
        this.dialogs = dialogs;
        this.i18n = i18n;
        this.localMetadata = localMetadata;
    }

    public void show(Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Синхронізація, плагіни та ШІ");
        dialog.setHeaderText("Керуйте синхронізацією користувацьких даних, розширеннями та провайдерами ШІ.");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(new Tab("WebDAV-синхронізація", webDavPane(owner)));
        tabs.getTabs().add(new Tab("Плагіни", pluginsPane(owner)));
        tabs.getTabs().add(new Tab("ШІ", aiPane(owner)));
        tabs.setPrefSize(940, 650);
        dialog.getDialogPane().setContent(tabs);
        i18n.apply(tabs);
        dialog.showAndWait();
    }

    private Node webDavPane(Window owner) {
        IntegrationBackend.WebDavConfiguration config = backend.webDavConfiguration();
        TextField endpoint = new TextField(config.endpoint());
        endpoint.setPromptText("https://cloud.example.com/remote.php/dav/files/user/MyHomeLib/");
        TextField username = new TextField(config.username());
        PasswordField password = new PasswordField();
        password.setPromptText(config.configured() ? "Залиште порожнім, щоб не змінювати" : "Пароль WebDAV");
        PasswordField syncKey = new PasswordField();
        syncKey.setPromptText(config.syncKey().isBlank() ? "Буде створено автоматично" : "Залиште порожнім, щоб не змінювати");

        Label secure = new Label(config.secureStoreAvailable()
                ? "Захищене сховище: " + config.secureStoreBackend()
                : "Захищене сховище недоступне — пароль і ключ не зберігатимуться у відкритому вигляді, тому WebDAV вимкнено.");
        secure.setWrapText(true);
        Label device = new Label("Ідентифікатор цього пристрою: " + config.deviceId());
        device.setWrapText(true);
        Label status = new Label(config.configured() ? "WebDAV налаштовано." : "WebDAV ще не налаштовано.");
        status.setWrapText(true);

        Button save = new Button("Зберегти");
        Button test = new Button("Перевірити з’єднання");
        Button sync = new Button("Синхронізувати зараз");
        Button copyKey = new Button("Копіювати ключ синхронізації");
        Button clear = new Button("Очистити налаштування");
        List<Button> actionButtons = List.of(save, test, sync, clear);

        save.setOnAction(e -> {
            IntegrationBackend.Result result = backend.saveWebDavConfiguration(
                    endpoint.getText(), username.getText(), password.getText(), syncKey.getText());
            showResult("WebDAV", result);
            password.clear();
            syncKey.clear();
            refreshWebDavFields(endpoint, username, secure, device, status, copyKey, test, sync);
        });
        test.setOnAction(e -> runWebDavTask(status, actionButtons, backend::testWebDav,
                result -> showResult("Перевірка WebDAV", result)));
        sync.setOnAction(e -> runWebDavSync(owner, status, actionButtons));
        copyKey.setDisable(config.syncKey().isBlank());
        copyKey.setOnAction(e -> copyText(backend.webDavConfiguration().syncKey(), "Ключ синхронізації скопійовано."));
        clear.setOnAction(e -> {
            if (!dialogs.showConfirmation("Очистити WebDAV", "Видалити локальні облікові дані WebDAV?",
                    "Віддалені зашифровані файли не видалятимуться.")) return;
            IntegrationBackend.Result result = backend.clearWebDavConfiguration();
            showResult("WebDAV", result);
            endpoint.clear(); username.clear(); password.clear(); syncKey.clear();
            refreshWebDavFields(endpoint, username, secure, device, status, copyKey, test, sync);
        });

        GridPane form = form();
        form.add(new Label("URL WebDAV"), 0, 0); form.add(endpoint, 1, 0);
        form.add(new Label("Користувач"), 0, 1); form.add(username, 1, 1);
        form.add(new Label("Пароль"), 0, 2); form.add(password, 1, 2);
        form.add(new Label("Ключ синхронізації"), 0, 3); form.add(syncKey, 1, 3);
        GridPane.setHgrow(endpoint, Priority.ALWAYS);
        GridPane.setHgrow(username, Priority.ALWAYS);
        GridPane.setHgrow(password, Priority.ALWAYS);
        GridPane.setHgrow(syncKey, Priority.ALWAYS);

        Label explanation = new Label("Синхронізуються користувацькі дані MyHomeLib (закладки, анотації, прогрес, оцінки та інші переносні дані). "
                + "Самі файли книг не завантажуються. Пакети на WebDAV шифруються ключем синхронізації. "
                + "Для другого пристрою використайте той самий URL, обліковий запис і ключ.");
        explanation.setWrapText(true);
        VBox box = section(explanation, form, new HBox(8, save, test, sync), new HBox(8, copyKey, clear),
                new Separator(), secure, device, status);
        if (!config.secureStoreAvailable()) {
            save.setDisable(true); test.setDisable(true); sync.setDisable(true); copyKey.setDisable(true);
        }
        return scroll(box);
    }

    private void refreshWebDavFields(TextField endpoint, TextField username, Label secure, Label device, Label status,
                                     Button copyKey, Button test, Button sync) {
        IntegrationBackend.WebDavConfiguration current = backend.webDavConfiguration();
        endpoint.setText(current.endpoint());
        username.setText(current.username());
        secure.setText(current.secureStoreAvailable()
                ? "Захищене сховище: " + current.secureStoreBackend()
                : "Захищене сховище недоступне — WebDAV вимкнено.");
        device.setText("Ідентифікатор цього пристрою: " + current.deviceId());
        status.setText(current.configured() ? "WebDAV налаштовано." : "WebDAV ще не налаштовано.");
        copyKey.setDisable(current.syncKey().isBlank());
        test.setDisable(!current.configured());
        sync.setDisable(!current.configured());
    }

    private void runWebDavSync(Window owner, Label status, List<Button> buttons) {
        setBusy(buttons, true);
        status.setText("Синхронізація виконується…");
        background.submit(backend::synchronizeWebDav).whenComplete((attempt, error) -> Platform.runLater(() -> {
            setBusy(buttons, false);
            if (error != null) {
                status.setText("Синхронізацію не виконано.");
                dialogs.showError("WebDAV", rootMessage(error));
                return;
            }
            status.setText(attempt.message());
            if (attempt.state() == IntegrationBackend.SyncState.FAILED) {
                dialogs.showError("WebDAV", attempt.message());
                return;
            }
            if (!attempt.hasConflict()) {
                dialogs.showInfo("WebDAV", attempt.message());
                return;
            }
            Optional<List<SyncConflictReviewSelection>> selection =
                    new SyncConflictReviewDialog(i18n).review(owner, attempt.conflicts());
            if (selection.isEmpty() || selection.get().isEmpty()) {
                status.setText("Конфлікт залишено без змін.");
                return;
            }
            IntegrationBackend.Result resolved = backend.resolveWebDavConflict(attempt.conflictToken(), selection.get());
            status.setText(resolved.message());
            showResult("WebDAV", resolved);
        }));
    }

    private void runWebDavTask(Label status, List<Button> buttons,
                               Supplier<IntegrationBackend.Result> task,
                               java.util.function.Consumer<IntegrationBackend.Result> done) {
        setBusy(buttons, true);
        status.setText("Перевірка виконується…");
        background.submit(task::get).whenComplete((result, error) -> Platform.runLater(() -> {
            setBusy(buttons, false);
            if (error != null) {
                status.setText("Перевірку не виконано.");
                dialogs.showError("WebDAV", rootMessage(error));
            } else {
                status.setText(result.message());
                done.accept(result);
            }
        }));
    }

    private Node pluginsPane(Window owner) {
        TableView<IntegrationBackend.PluginInfo> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<IntegrationBackend.PluginInfo, String> name = column("Плагін", row -> row.displayName());
        TableColumn<IntegrationBackend.PluginInfo, String> version = column("Версія", row -> row.version());
        TableColumn<IntegrationBackend.PluginInfo, String> state = column("Стан", row -> pluginState(row.state()));
        TableColumn<IntegrationBackend.PluginInfo, String> permissions = column("Дозволи", row ->
                row.permissions().isEmpty() ? "Немає" : row.permissions().stream().map(this::permissionName).reduce((a,b) -> a + ", " + b).orElse(""));
        table.getColumns().setAll(List.of(name, version, state, permissions));
        table.setPrefHeight(330);

        TextArea details = new TextArea();
        details.setEditable(false);
        details.setWrapText(true);
        details.setPrefRowCount(8);
        Button install = new Button("Встановити JAR…");
        Button enable = new Button("Увімкнути");
        Button disable = new Button("Вимкнути");
        Button remove = new Button("Видалити");
        Button refresh = new Button("Оновити список");
        Button inspectMetadata = new Button("Витягти метадані файла…");
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            details.setText(pluginDetails(selected));
            remove.setDisable(selected == null || !selected.installedPackage());
        });
        Runnable reload = () -> {
            IntegrationBackend.PluginInfo selected = table.getSelectionModel().getSelectedItem();
            String selectedId = selected == null ? "" : selected.pluginId();
            table.getItems().setAll(backend.plugins());
            table.getItems().stream().filter(p -> p.pluginId().equals(selectedId)).findFirst()
                    .ifPresent(table.getSelectionModel()::select);
            if (table.getSelectionModel().getSelectedItem() == null && !table.getItems().isEmpty()) {
                table.getSelectionModel().selectFirst();
            }
        };
        reload.run();

        install.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Виберіть JAR-плагін MyHomeLib");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JAR-файли (*.jar)", "*.jar"));
            File file = chooser.showOpenDialog(owner);
            if (file == null) return;
            IntegrationBackend.Result result = backend.installPlugin(file.toPath());
            showResult("Плагіни", result);
            reload.run();
        });
        enable.setOnAction(e -> {
            IntegrationBackend.PluginInfo selected = requirePlugin(table);
            if (selected == null) return;
            String requested = selected.permissions().isEmpty() ? "Плагін не запитує додаткових дозволів."
                    : "Запитані дозволи:\n• " + selected.permissions().stream().map(this::permissionName).reduce((a,b) -> a + "\n• " + b).orElse("");
            String fingerprint = selected.packageSha256().isBlank() ? "невідомий" : selected.packageSha256();
            boolean confirmed = dialogs.showConfirmation("Увімкнути плагін",
                    "Довіряти цій версії «" + selected.displayName() + "»?",
                    requested + "\n\nВідбиток пакета:\n" + fingerprint
                            + "\n\nПлагін виконується у процесі MyHomeLib. Увімкнення означає довіру до цієї конкретної версії та її відбитка.");
            if (!confirmed) return;
            showResult("Плагіни", backend.enablePlugin(selected.pluginId(), true));
            reload.run();
        });
        disable.setOnAction(e -> {
            IntegrationBackend.PluginInfo selected = requirePlugin(table);
            if (selected == null) return;
            showResult("Плагіни", backend.disablePlugin(selected.pluginId()));
            reload.run();
        });
        remove.setOnAction(e -> {
            IntegrationBackend.PluginInfo selected = requirePlugin(table);
            if (selected == null) return;
            if (!dialogs.showConfirmation("Видалити плагін", "Видалити «" + selected.displayName() + "»?",
                    "Буде видалено JAR плагіна. Дані бібліотеки автоматично не видаляються.")) return;
            showResult("Плагіни", backend.removePlugin(selected.pluginId()));
            reload.run();
        });
        refresh.setOnAction(e -> reload.run());
        inspectMetadata.setOnAction(e -> inspectLocalMetadata(owner));

        Label intro = new Label("Зовнішній JAR спочатку читається як маніфест без запуску коду. Код завантажується лише після вашого явного підтвердження. "
                + "Схвалення прив’язане до версії та відбитка пакета; підмінений зовнішній JAR потребуватиме нового дозволу. Вбудовані плагіни постачаються разом із MyHomeLib і можуть бути вимкнені.");
        intro.setWrapText(true);
        VBox box = section(intro, table, new HBox(8, install, enable, disable, remove, refresh),
                new HBox(8, inspectMetadata), new Label("Відомості"), details);
        VBox.setVgrow(table, Priority.ALWAYS);
        return box;
    }


    private void inspectLocalMetadata(Window owner) {
        if (!localMetadata.hasExtractors()) {
            dialogs.showInfo("Метадані файла", "Немає увімкнених плагінів, які вміють витягувати метадані з локальних файлів.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Виберіть файл для аналізу метаданих");
        File file = chooser.showOpenDialog(owner);
        if (file == null) return;
        background.submit(() -> localMetadata.extract(file.toPath())).whenComplete((results, error) -> Platform.runLater(() -> {
            if (error != null) {
                dialogs.showError("Метадані файла", "Не вдалося витягти метадані: " + rootMessage(error));
                return;
            }
            if (results == null || results.isEmpty()) {
                dialogs.showInfo("Метадані файла", "Жоден активний екстрактор не підтримує цей файл.");
                return;
            }
            StringBuilder text = new StringBuilder();
            for (LocalMetadataExtractionService.Extraction result : results) {
                if (!text.isEmpty()) text.append("\n\n");
                text.append("Провайдер: ").append(result.providerId()).append('\n');
                if (result.values().isEmpty()) {
                    text.append("Метадані не знайдено.");
                } else {
                    result.values().entrySet().stream().sorted(Map.Entry.comparingByKey())
                            .forEach(entry -> text.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n'));
                }
            }
            TextArea area = new TextArea(text.toString().stripTrailing());
            area.setEditable(false);
            area.setWrapText(true);
            area.setPrefSize(720, 480);
            Dialog<ButtonType> dialog = new Dialog<>();
            if (owner != null) dialog.initOwner(owner);
            dialog.setTitle("Метадані файла");
            dialog.setHeaderText(file.getName());
            dialog.getDialogPane().setContent(area);
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            dialog.showAndWait();
        }));
    }

    private Node aiPane(Window owner) {
        IntegrationBackend.AiConfiguration config = backend.aiConfiguration();
        TextField endpoint = new TextField(config.endpoint());
        TextField model = new TextField(config.model());
        Spinner<Integer> maxTokens = new Spinner<>(1, 32768, Math.max(1, config.maxOutputTokens()), 256);
        maxTokens.setEditable(true);
        PasswordField apiKey = new PasswordField();
        apiKey.setPromptText(config.apiKeyStored() ? "Ключ уже збережено; залиште порожнім, щоб не змінювати" : "Ключ API");
        Label keyState = new Label(config.apiKeyStored() ? "Ключ OpenAI збережено у захищеному сховищі." : "Ключ OpenAI не збережено.");
        Label secure = new Label(config.secureStoreAvailable()
                ? "Захищене сховище: " + config.secureStoreBackend()
                : "Захищене сховище недоступне. Провайдери, яким потрібні секрети, не працюватимуть.");
        secure.setWrapText(true);

        Button save = new Button("Зберегти налаштування OpenAI");
        Button clearKey = new Button("Видалити ключ OpenAI");
        save.setOnAction(e -> {
            IntegrationBackend.Result result = backend.saveAiConfiguration(endpoint.getText(), model.getText(), maxTokens.getValue(), apiKey.getText());
            apiKey.clear();
            showResult("ШІ", result);
            IntegrationBackend.AiConfiguration now = backend.aiConfiguration();
            keyState.setText(now.apiKeyStored() ? "Ключ OpenAI збережено у захищеному сховищі." : "Ключ OpenAI не збережено.");
        });
        clearKey.setOnAction(e -> {
            showResult("ШІ", backend.clearAiApiKey());
            keyState.setText("Ключ OpenAI не збережено.");
        });

        GridPane form = form();
        form.add(new Label("URL сервісу"), 0, 0); form.add(endpoint, 1, 0);
        form.add(new Label("Модель"), 0, 1); form.add(model, 1, 1);
        form.add(new Label("Макс. токенів відповіді"), 0, 2); form.add(maxTokens, 1, 2);
        form.add(new Label("Ключ API"), 0, 3); form.add(apiKey, 1, 3);
        GridPane.setHgrow(endpoint, Priority.ALWAYS); GridPane.setHgrow(model, Priority.ALWAYS); GridPane.setHgrow(apiKey, Priority.ALWAYS);

        ListView<IntegrationBackend.AiProviderInfo> providers = new ListView<>();
        providers.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(IntegrationBackend.AiProviderInfo item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName() + "  [" + item.id() + "]");
            }
        });
        providers.getItems().setAll(backend.aiProviders());
        providers.setPrefHeight(150);
        TextArea providerDetails = new TextArea();
        providerDetails.setEditable(false); providerDetails.setWrapText(true); providerDetails.setPrefRowCount(5);
        providers.getSelectionModel().selectedItemProperty().addListener((o,a,b) -> providerDetails.setText(aiProviderDetails(b)));
        if (!providers.getItems().isEmpty()) providers.getSelectionModel().selectFirst();

        Button setSecrets = new Button("Зберегти секрети провайдера…");
        Button deleteSecret = new Button("Видалити секрет…");
        setSecrets.setOnAction(e -> configureProviderSecrets(owner, providers.getSelectionModel().getSelectedItem()));
        deleteSecret.setOnAction(e -> deleteProviderSecret(providers.getSelectionModel().getSelectedItem()));

        Label privacy = new Label("ШІ не запускається автоматично. Перед кожною дією з книгою MyHomeLib показує провайдера та окремо просить дозвіл "
                + "на передачу тексту книги і, якщо потрібно, на мережевий запит. Ключі зберігаються тільки через системне захищене сховище.");
        privacy.setWrapText(true);

        VBox box = section(privacy, new Label("Вбудований провайдер OpenAI"), form, new HBox(8, save, clearKey), keyState, secure,
                new Separator(), new Label("Доступні провайдери"), providers, providerDetails, new HBox(8, setSecrets, deleteSecret));
        return scroll(box);
    }

    private void configureProviderSecrets(Window owner, IntegrationBackend.AiProviderInfo provider) {
        if (provider == null) { dialogs.showWarning("ШІ", "Спочатку виберіть провайдера."); return; }
        if (provider.requiredSecrets().isEmpty()) { dialogs.showInfo("ШІ", "Цей провайдер не потребує секретів."); return; }
        if (!backend.aiConfiguration().secureStoreAvailable()) { dialogs.showError("ШІ", "Захищене сховище секретів недоступне."); return; }

        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Секрети провайдера ШІ");
        dialog.setHeaderText(provider.displayName());
        ButtonType save = new ButtonType("Зберегти", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);
        GridPane grid = form();
        List<SecretEditor> fields = new ArrayList<>();
        int row = 0;
        for (String secret : provider.requiredSecrets()) {
            PasswordField field = new PasswordField();
            field.setPromptText("Залиште порожнім, щоб не змінювати");
            fields.add(new SecretEditor(secret, field));
            grid.add(new Label(secret), 0, row); grid.add(field, 1, row++);
            GridPane.setHgrow(field, Priority.ALWAYS);
        }
        dialog.getDialogPane().setContent(grid);
        if (dialog.showAndWait().filter(save::equals).isEmpty()) return;
        for (SecretEditor editor : fields) {
            String value = editor.field().getText();
            if (value == null || value.isBlank()) continue;
            IntegrationBackend.Result result = backend.saveAiProviderSecret(provider.id(), editor.name(), value);
            editor.field().clear();
            if (!result.success()) { showResult("ШІ", result); return; }
        }
        dialogs.showInfo("ШІ", "Введені секрети збережено у захищеному сховищі.");
    }

    private void deleteProviderSecret(IntegrationBackend.AiProviderInfo provider) {
        if (provider == null) { dialogs.showWarning("ШІ", "Спочатку виберіть провайдера."); return; }
        if (provider.requiredSecrets().isEmpty()) { dialogs.showInfo("ШІ", "Цей провайдер не потребує секретів."); return; }
        Optional<String> selected = dialogs.showChoiceDialog(provider.requiredSecrets(), provider.requiredSecrets().getFirst(),
                "Видалити секрет", provider.displayName(), "Секрет");
        selected.ifPresent(name -> showResult("ШІ", backend.deleteAiProviderSecret(provider.id(), name)));
    }

    private IntegrationBackend.PluginInfo requirePlugin(TableView<IntegrationBackend.PluginInfo> table) {
        IntegrationBackend.PluginInfo selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) dialogs.showWarning("Плагіни", "Спочатку виберіть плагін.");
        return selected;
    }

    private String pluginDetails(IntegrationBackend.PluginInfo p) {
        if (p == null) return "";
        String services = p.services().isEmpty() ? "Немає" : p.services().stream().map(this::serviceName).reduce((a,b) -> a + ", " + b).orElse("");
        String permissions = p.permissions().isEmpty() ? "Немає" : p.permissions().stream().map(this::permissionName).reduce((a,b) -> a + ", " + b).orElse("");
        String failure = p.failure().isBlank() ? "" : "\nПомилка: " + p.failure();
        return "ID: " + p.pluginId() + "\nНазва: " + p.displayName() + "\nВерсія: " + p.version()
                + "\nСтан: " + pluginState(p.state()) + "\nДовіра: " + trustName(p.trust())
                + "\nМожливості: " + services + "\nДозволи: " + permissions
                + "\nSHA-256: " + p.packageSha256() + "\nФайл: " + p.source() + failure;
    }

    private String aiProviderDetails(IntegrationBackend.AiProviderInfo p) {
        if (p == null) return "";
        String operations = p.operations().stream().map(this::aiOperationName).reduce((a,b) -> a + ", " + b).orElse("Немає");
        String secrets = p.requiredSecrets().isEmpty() ? "не потрібні" : String.join(", ", p.requiredSecrets());
        return "ID: " + p.id() + "\nОперації: " + operations + "\nМережа: " + (p.networkRequired() ? "потрібна" : "не потрібна")
                + "\nСекрети: " + secrets;
    }

    private String pluginState(String raw) {
        return switch (upper(raw)) {
            case "ENABLED" -> "Увімкнено";
            case "DISABLED" -> "Вимкнено";
            case "QUARANTINED" -> "Ізольовано після помилки";
            case "FAILED" -> "Помилка";
            default -> raw == null ? "" : raw;
        };
    }

    private String trustName(String raw) {
        return switch (upper(raw)) {
            case "TRUSTED" -> "Схвалено користувачем";
            case "UNTRUSTED" -> "Не схвалено";
            default -> raw == null ? "" : raw;
        };
    }

    private String permissionName(String raw) {
        return switch (upper(raw)) {
            case "NETWORK_ACCESS" -> "доступ до мережі";
            case "FILESYSTEM_READ" -> "читання файлів";
            case "FILESYSTEM_WRITE" -> "запис файлів";
            case "EXTERNAL_PROCESS_EXECUTION" -> "запуск зовнішніх програм";
            default -> raw == null ? "" : raw;
        };
    }

    private String serviceName(String raw) {
        return switch (upper(raw)) {
            case "METADATA_PROVIDER" -> "метадані";
            case "COVER_PROVIDER" -> "обкладинки";
            case "BOOK_IMPORTER" -> "імпорт книг";
            case "BOOK_CONVERTER" -> "конвертація книг";
            case "METADATA_EXTRACTOR" -> "витяг метаданих";
            case "CONTENT_EXTRACTOR" -> "витяг тексту";
            case "EXPORT_PROVIDER" -> "експорт";
            case "TRANSLATION_PROVIDER" -> "переклад";
            case "DICTIONARY_PROVIDER" -> "словник";
            case "DEVICE_PROVIDER" -> "пристрої";
            case "AI_PROVIDER" -> "провайдер ШІ";
            default -> raw == null ? "" : raw;
        };
    }

    private String aiOperationName(String raw) {
        return switch (upper(raw)) {
            case "SUMMARY" -> "підсумок";
            case "QUESTION_ANSWER" -> "запитання й відповіді";
            default -> raw == null ? "" : raw;
        };
    }

    private static String upper(String value) { return value == null ? "" : value.toUpperCase(Locale.ROOT); }

    private <T> TableColumn<IntegrationBackend.PluginInfo, String> column(String title,
                                                                           java.util.function.Function<IntegrationBackend.PluginInfo, String> value) {
        TableColumn<IntegrationBackend.PluginInfo, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return column;
    }

    private void showResult(String title, IntegrationBackend.Result result) {
        if (result.success()) dialogs.showInfo(title, result.message());
        else dialogs.showError(title, result.message());
    }

    private void copyText(String value, String successMessage) {
        if (value == null || value.isBlank()) { dialogs.showWarning("Копіювання", "Немає значення для копіювання."); return; }
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        Clipboard.getSystemClipboard().setContent(content);
        dialogs.showInfo("Копіювання", successMessage);
    }

    private static void setBusy(List<Button> buttons, boolean busy) { buttons.forEach(button -> button.setDisable(busy)); }

    private static GridPane form() {
        GridPane grid = new GridPane();
        grid.setHgap(12); grid.setVgap(10); grid.setPadding(new Insets(4));
        return grid;
    }

    private static VBox section(Node... nodes) {
        VBox box = new VBox(12, nodes);
        box.setPadding(new Insets(14));
        return box;
    }

    private static ScrollPane scroll(Node node) {
        ScrollPane scroll = new ScrollPane(node);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) current = current.getCause();
        if (current == null) return "Невідома помилка";
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record SecretEditor(String name, PasswordField field) { }
}
