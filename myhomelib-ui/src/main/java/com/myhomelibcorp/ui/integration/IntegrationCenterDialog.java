package com.myhomelibcorp.ui.integration;

import com.myhomelibcorp.ui.service.DialogLocalization;
import com.myhomelibcorp.ui.util.UiExceptionSupport;

import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.sync.conflict.SyncConflictReviewSelection;
import com.myhomelibcorp.application.extension.LocalMetadataExtractionService;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FileChooserService;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** User-facing management center for cross-device sync, external plugins and AI providers. */
@Slf4j
@Component
public final class IntegrationCenterDialog {
    private final IntegrationBackend backend;
    private final UiBackgroundExecutor background;
    private final DialogService dialogs;
    private final LocalizationService i18n;
    private final LocalMetadataExtractionService localMetadata;
    private final FileChooserService fileChooserService;

    public IntegrationCenterDialog(IntegrationBackend backend,
                                   UiBackgroundExecutor background,
                                   DialogService dialogs,
                                   LocalizationService i18n,
                                   LocalMetadataExtractionService localMetadata,
                                   FileChooserService fileChooserService) {
        this.backend = backend;
        this.background = background;
        this.dialogs = dialogs;
        this.i18n = i18n;
        this.localMetadata = localMetadata;
        this.fileChooserService = fileChooserService;
    }

    public void show(Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        DialogLocalization.prepare(dialog);
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(UiText.text("ui.java.integration_center_dialog.299f01059a"));
        dialog.setHeaderText(UiText.text("ui.java.integration_center_dialog.42e4d5ac87"));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(new Tab(UiText.text("ui.java.integration_center_dialog.a08b1eb970"), webDavPane(owner)));
        tabs.getTabs().add(new Tab(UiText.text("ui.integration.kobo.tab"), koboPane(owner)));
        tabs.getTabs().add(new Tab(UiText.text("ui.integration.readwise.tab"), readwisePane(owner)));
        tabs.getTabs().add(new Tab(UiText.text("ui.java.integration_center_dialog.ccecfa5e49"), pluginsPane(owner)));
        tabs.getTabs().add(new Tab(UiText.text("ui.java.integration_center_dialog.fdc40cc5d5"), aiPane(owner)));
        tabs.setPrefSize(940, 650);
        dialog.getDialogPane().setContent(tabs);
        i18n.apply(tabs);
        dialog.showAndWait();
    }

    private Node readwisePane(Window owner) {
        PasswordField token = new PasswordField();
        token.setPromptText(UiText.text("ui.integration.readwise.token_hint"));
        Label status = new Label();
        status.setWrapText(true);
        Button save = new Button(UiText.text("common.save"));
        save.setId("readwise-save-token");
        Button test = new Button(UiText.text("ui.integration.readwise.test"));
        test.setId("readwise-test");
        Button export = new Button(UiText.text("ui.integration.readwise.export_all"));
        export.setId("readwise-export-all");
        Button clear = new Button(UiText.text("ui.integration.readwise.clear"));
        clear.setId("readwise-clear-token");

        Runnable refresh = () -> {
            IntegrationBackend.ReadwiseStatus current = backend.readwiseStatus();
            status.setText(current.secureStoreAvailable()
                    ? UiText.text(current.configured() ? "ui.integration.readwise.configured" : "ui.integration.readwise.not_configured")
                    + " — " + current.secureStoreBackend()
                    : UiText.text("ui.integration.readwise.secure_store_unavailable"));
            save.setDisable(!current.secureStoreAvailable());
            test.setDisable(!current.configured()); export.setDisable(!current.configured()); clear.setDisable(!current.configured());
        };
        refresh.run();
        save.setOnAction(e -> {
            IntegrationBackend.Result result = backend.saveReadwiseToken(token.getText());
            token.clear(); showResult(UiText.text("ui.integration.readwise.tab"), result); refresh.run();
        });
        test.setOnAction(e -> showResult(UiText.text("ui.integration.readwise.tab"), backend.testReadwise()));
        export.setOnAction(e -> showResult(UiText.text("ui.integration.readwise.tab"), backend.exportAnnotationsToReadwise()));
        clear.setOnAction(e -> {
            if (!dialogs.showConfirmation(UiText.text("ui.integration.readwise.clear"),
                    UiText.text("ui.integration.readwise.clear_confirm"), UiText.text("ui.integration.readwise.clear_confirm_detail"))) return;
            showResult(UiText.text("ui.integration.readwise.tab"), backend.clearReadwiseToken()); refresh.run();
        });
        Label help = new Label(UiText.text("ui.integration.readwise.help")); help.setWrapText(true);
        return scroll(section(help, token, new HBox(8, save, test, export, clear), new Separator(), status));
    }

    private Node koboPane(Window owner) {
        IntegrationBackend.KoboStatus current = backend.koboStatus();
        Label enabled = new Label();
        Label secure = new Label();
        Label device = new Label();
        Label lastSync = new Label();
        Label lastError = new Label();
        TextArea credential = new TextArea();
        credential.setEditable(false);
        credential.setWrapText(true);
        credential.setPrefRowCount(3);
        credential.setPromptText(UiText.text("ui.integration.kobo.credential_once"));

        Button enableRotate = new Button(UiText.text(current.enabled() ? "ui.integration.kobo.rotate" : "ui.integration.kobo.enable"));
        Button copy = new Button(UiText.text("ui.integration.kobo.copy_credential"));
        Button syncNow = new Button(UiText.text("ui.integration.kobo.sync_now"));
        Button revoke = new Button(UiText.text("ui.integration.kobo.revoke"));
        copy.setDisable(true);

        Runnable refresh = () -> {
            IntegrationBackend.KoboStatus status = backend.koboStatus();
            enabled.setText(UiText.text(status.enabled() ? "ui.integration.kobo.status_enabled" : "ui.integration.kobo.status_disabled"));
            secure.setText(status.secureStoreAvailable()
                    ? UiText.text("ui.integration.kobo.secure_store") + status.secureStoreBackend()
                    : UiText.text("ui.integration.kobo.secure_store_unavailable"));
            device.setText(UiText.text("ui.integration.kobo.device_id") + (status.deviceId().isBlank() ? "—" : UiText.text("ui.integration.device.configured")));
            lastSync.setText(UiText.text("ui.integration.kobo.last_sync") + (status.lastSyncAt().isBlank() ? "—" : status.lastSyncAt()));
            lastError.setText(UiText.text("ui.integration.kobo.last_error") + (status.lastError().isBlank() ? "—" : status.lastError()));
            enableRotate.setText(UiText.text(status.enabled() ? "ui.integration.kobo.rotate" : "ui.integration.kobo.enable"));
            enableRotate.setDisable(!status.secureStoreAvailable());
            revoke.setDisable(!status.enabled());
            syncNow.setDisable(!status.enabled());
        };
        refresh.run();

        enableRotate.setOnAction(e -> {
            IntegrationBackend.KoboCredential issued = backend.enableOrRotateKobo();
            showResult(UiText.text("ui.integration.kobo.tab"), issued.result());
            if (issued.result().success() && !issued.token().isBlank()) {
                credential.setText(issued.token());
                copy.setDisable(false);
            }
            refresh.run();
        });
        copy.setOnAction(e -> copyText(credential.getText(), UiText.text("ui.integration.kobo.copied")));
        syncNow.setOnAction(e -> {
            IntegrationBackend.Result result = backend.synchronizeKoboNow();
            showResult(UiText.text("ui.integration.kobo.tab"), result);
            refresh.run();
        });
        revoke.setOnAction(e -> {
            if (!dialogs.showConfirmation(UiText.text("ui.integration.kobo.revoke"),
                    UiText.text("ui.integration.kobo.revoke_confirm"), UiText.text("ui.integration.kobo.revoke_confirm_detail"))) return;
            IntegrationBackend.Result result = backend.revokeKobo();
            credential.clear();
            copy.setDisable(true);
            showResult(UiText.text("ui.integration.kobo.tab"), result);
            refresh.run();
        });

        Label explanation = new Label(UiText.text("ui.integration.kobo.help"));
        explanation.setWrapText(true);
        return scroll(section(explanation, new HBox(8, enableRotate, syncNow, revoke), credential, copy,
                new Separator(), enabled, secure, device, lastSync, lastError));
    }

    private Node webDavPane(Window owner) {
        IntegrationBackend.WebDavConfiguration config = backend.webDavConfiguration();
        TextField endpoint = new TextField(config.endpoint());
        endpoint.setPromptText(UiText.text("ui.integration.webdav_url_example"));
        TextField username = new TextField(config.username());
        PasswordField password = new PasswordField();
        password.setPromptText(config.configured() ? UiText.text("ui.java.integration_center_dialog.3982eae417") : UiText.text("ui.java.integration_center_dialog.c4fd908f1e"));
        PasswordField syncKey = new PasswordField();
        syncKey.setPromptText(config.syncKey().isBlank() ? UiText.text("ui.java.integration_center_dialog.ce76e9675a") : UiText.text("ui.java.integration_center_dialog.3982eae417"));

        Label secure = new Label(config.secureStoreAvailable()
                ? UiText.text("ui.java.integration_center_dialog.c3f7718ea3") + config.secureStoreBackend()
                : UiText.text("ui.java.integration_center_dialog.0ac1a72697"));
        secure.setWrapText(true);
        Label device = new Label(UiText.text("ui.java.integration_center_dialog.c2253ff68b") + (config.deviceId().isBlank() ? "—" : UiText.text("ui.integration.device.configured")));
        device.setWrapText(true);
        Label status = new Label(config.configured() ? UiText.text("ui.java.integration_center_dialog.42ab129878") : UiText.text("ui.java.integration_center_dialog.13dc2d6776"));
        status.setWrapText(true);

        Button save = new Button(UiText.text("common.save"));
        Button test = new Button(UiText.text("ui.java.integration_center_dialog.9b85566c48"));
        Button sync = new Button(UiText.text("ui.java.integration_center_dialog.e0f3361d2f"));
        Button copyKey = new Button(UiText.text("ui.java.integration_center_dialog.7c8e277143"));
        Button clear = new Button(UiText.text("ui.java.integration_center_dialog.73e28750ff"));
        List<Button> actionButtons = List.of(save, test, sync, clear);

        save.setOnAction(e -> {
            IntegrationBackend.Result result = backend.saveWebDavConfiguration(
                    endpoint.getText(), username.getText(), password.getText(), syncKey.getText());
            showResult(UiText.text("ui.integration.webdav_title"), result);
            password.clear();
            syncKey.clear();
            refreshWebDavFields(endpoint, username, secure, device, status, copyKey, test, sync);
        });
        test.setOnAction(e -> runWebDavTask(status, actionButtons, backend::testWebDav,
                result -> showResult(UiText.text("ui.java.integration_center_dialog.c87a85009c"), result)));
        sync.setOnAction(e -> runWebDavSync(owner, status, actionButtons));
        copyKey.setDisable(config.syncKey().isBlank());
        copyKey.setOnAction(e -> copyText(backend.webDavConfiguration().syncKey(), UiText.text("ui.java.integration_center_dialog.2453dd7804")));
        clear.setOnAction(e -> {
            if (!dialogs.showConfirmation(UiText.text("ui.java.integration_center_dialog.924729c321"), UiText.text("ui.java.integration_center_dialog.66587202be"),
                    UiText.text("ui.java.integration_center_dialog.478207f6ee"))) return;
            IntegrationBackend.Result result = backend.clearWebDavConfiguration();
            showResult(UiText.text("ui.integration.webdav_title"), result);
            endpoint.clear(); username.clear(); password.clear(); syncKey.clear();
            refreshWebDavFields(endpoint, username, secure, device, status, copyKey, test, sync);
        });

        GridPane form = form();
        form.add(new Label(UiText.text("ui.integration.webdav_url")), 0, 0); form.add(endpoint, 1, 0);
        form.add(new Label(UiText.text("ui.java.integration_center_dialog.8e5cb01b3b")), 0, 1); form.add(username, 1, 1);
        form.add(new Label(UiText.text("ui.java.integration_center_dialog.14f7c63cc1")), 0, 2); form.add(password, 1, 2);
        form.add(new Label(UiText.text("ui.java.integration_center_dialog.794b158eb5")), 0, 3); form.add(syncKey, 1, 3);
        GridPane.setHgrow(endpoint, Priority.ALWAYS);
        GridPane.setHgrow(username, Priority.ALWAYS);
        GridPane.setHgrow(password, Priority.ALWAYS);
        GridPane.setHgrow(syncKey, Priority.ALWAYS);

        Label explanation = new Label(UiText.text("ui.java.integration_center_dialog.213804d104")
                + UiText.text("ui.java.integration_center_dialog.85506a0fa1")
                + UiText.text("ui.java.integration_center_dialog.be18fc1116"));
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
                ? UiText.text("ui.java.integration_center_dialog.c3f7718ea3") + current.secureStoreBackend()
                : UiText.text("ui.java.integration_center_dialog.6c791a152b"));
        device.setText(UiText.text("ui.java.integration_center_dialog.c2253ff68b") + (current.deviceId().isBlank() ? "—" : UiText.text("ui.integration.device.configured")));
        status.setText(current.configured() ? UiText.text("ui.java.integration_center_dialog.42ab129878") : UiText.text("ui.java.integration_center_dialog.13dc2d6776"));
        copyKey.setDisable(current.syncKey().isBlank());
        test.setDisable(!current.configured());
        sync.setDisable(!current.configured());
    }

    private void runWebDavSync(Window owner, Label status, List<Button> buttons) {
        setBusy(buttons, true);
        status.setText(UiText.text("ui.java.integration_center_dialog.4a28c00989"));
        background.submitCollectionScoped(LibraryOperationType.SYNC, backend::synchronizeWebDav)
                .whenComplete((attempt, error) -> Platform.runLater(() -> {
            setBusy(buttons, false);
            if (error != null) {
                status.setText(UiText.text("ui.java.integration_center_dialog.91ed83159c"));
                dialogs.showError(UiText.text("ui.integration.webdav_title"), rootMessage(error));
                return;
            }
            status.setText(renderMessage(attempt.userMessage()));
            if (attempt.state() == IntegrationBackend.SyncState.FAILED) {
                dialogs.showError(UiText.text("ui.integration.webdav_title"), renderMessage(attempt.userMessage()));
                return;
            }
            if (!attempt.hasConflict()) {
                dialogs.showInfo(UiText.text("ui.integration.webdav_title"), renderMessage(attempt.userMessage()));
                return;
            }
            Optional<List<SyncConflictReviewSelection>> selection =
                    new SyncConflictReviewDialog(i18n).review(owner, attempt.conflicts());
            if (selection.isEmpty() || selection.get().isEmpty()) {
                status.setText(UiText.text("ui.java.integration_center_dialog.63c43903ad"));
                return;
            }
            setBusy(buttons, true);
            status.setText(UiText.text("ui.java.integration_center_dialog.4a28c00989"));
            background.submitCollectionScoped(LibraryOperationType.SYNC,
                            () -> backend.resolveWebDavConflict(attempt.conflictToken(), selection.get()))
                    .whenComplete((resolved, resolveError) -> Platform.runLater(() -> {
                        setBusy(buttons, false);
                        if (resolveError != null) {
                            status.setText(UiText.text("ui.java.integration_center_dialog.91ed83159c"));
                            dialogs.showError(UiText.text("ui.integration.webdav_title"), rootMessage(resolveError));
                            return;
                        }
                        status.setText(renderMessage(resolved.userMessage()));
                        showResult(UiText.text("ui.integration.webdav_title"), resolved);
                    }));
        }));
    }

    private void runWebDavTask(Label status, List<Button> buttons,
                               Supplier<IntegrationBackend.Result> task,
                               java.util.function.Consumer<IntegrationBackend.Result> done) {
        setBusy(buttons, true);
        status.setText(UiText.text("ui.java.integration_center_dialog.7aba7b9e0d"));
        background.submit(task::get).whenComplete((result, error) -> Platform.runLater(() -> {
            setBusy(buttons, false);
            if (error != null) {
                status.setText(UiText.text("ui.java.integration_center_dialog.dbf34f6732"));
                dialogs.showError(UiText.text("ui.integration.webdav_title"), rootMessage(error));
            } else {
                status.setText(renderMessage(result.userMessage()));
                done.accept(result);
            }
        }));
    }

    private Node pluginsPane(Window owner) {
        TableView<IntegrationBackend.PluginInfo> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<IntegrationBackend.PluginInfo, String> name = column(UiText.text("ui.java.integration_center_dialog.4da261a691"), row -> row.displayName());
        TableColumn<IntegrationBackend.PluginInfo, String> version = column(UiText.text("ui.java.integration_center_dialog.44724bc770"), row -> row.version());
        TableColumn<IntegrationBackend.PluginInfo, String> state = column(UiText.text("ui.fxml.operation_center_workspace.state"), row -> pluginState(row.state()));
        TableColumn<IntegrationBackend.PluginInfo, String> permissions = column(UiText.text("ui.java.integration_center_dialog.4a1bd9e562"), row ->
                row.permissions().isEmpty() ? UiText.text("ui.java.integration_center_dialog.d382957ff0") : row.permissions().stream().map(this::permissionName).reduce((a,b) -> a + ", " + b).orElse(""));
        table.getColumns().setAll(List.of(name, version, state, permissions));
        table.setPrefHeight(330);

        TextArea details = new TextArea();
        details.setEditable(false);
        details.setWrapText(true);
        details.setPrefRowCount(8);
        Button install = new Button(UiText.text("ui.java.integration_center_dialog.174687fc7f"));
        Button enable = new Button(UiText.text("ui.java.integration_center_dialog.bccfe21f3a"));
        Button disable = new Button(UiText.text("ui.java.integration_center_dialog.f827ee7659"));
        Button remove = new Button(UiText.text("common.delete"));
        Button refresh = new Button(UiText.text("ui.opds.tokens.refresh"));
        Button inspectMetadata = new Button(UiText.text("ui.java.integration_center_dialog.2296e02bc3"));
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
            File file = fileChooserService.chooseFile(owner,
                    UiText.text("ui.java.integration_center_dialog.88da6ef7dc"),
                    List.of(new FileChooser.ExtensionFilter(UiText.text("ui.java.integration_center_dialog.d018ebf033"), "*.jar")));
            if (file == null) return;
            IntegrationBackend.Result result = backend.installPlugin(file.toPath());
            showResult(UiText.text("ui.java.integration_center_dialog.ccecfa5e49"), result);
            reload.run();
        });
        enable.setOnAction(e -> {
            IntegrationBackend.PluginInfo selected = requirePlugin(table);
            if (selected == null) return;
            String requested = selected.permissions().isEmpty() ? UiText.text("ui.java.integration_center_dialog.f255c31049")
                    : UiText.text("ui.java.integration_center_dialog.23a12524b4") + selected.permissions().stream().map(this::permissionName).reduce((a,b) -> a + "\n• " + b).orElse("");
            boolean confirmed = dialogs.showConfirmation(UiText.text("ui.java.integration_center_dialog.f2fbdd82ed"),
                    UiText.text("ui.java.integration_center_dialog.379cf312b4") + selected.displayName() + "»?",
                    requested + UiText.text("ui.java.integration_center_dialog.01de2a3143"));
            if (!confirmed) return;
            showResult(UiText.text("ui.java.integration_center_dialog.ccecfa5e49"), backend.enablePlugin(selected.pluginId(), true));
            reload.run();
        });
        disable.setOnAction(e -> {
            IntegrationBackend.PluginInfo selected = requirePlugin(table);
            if (selected == null) return;
            showResult(UiText.text("ui.java.integration_center_dialog.ccecfa5e49"), backend.disablePlugin(selected.pluginId()));
            reload.run();
        });
        remove.setOnAction(e -> {
            IntegrationBackend.PluginInfo selected = requirePlugin(table);
            if (selected == null) return;
            if (!dialogs.showConfirmation(UiText.text("ui.java.integration_center_dialog.bc27f0acdf"), UiText.text("ui.java.integration_center_dialog.77b5e9e362") + selected.displayName() + "»?",
                    UiText.text("ui.java.integration_center_dialog.62050f1b91"))) return;
            showResult(UiText.text("ui.java.integration_center_dialog.ccecfa5e49"), backend.removePlugin(selected.pluginId()));
            reload.run();
        });
        refresh.setOnAction(e -> reload.run());
        inspectMetadata.setOnAction(e -> inspectLocalMetadata(owner));

        Label intro = new Label(UiText.text("ui.java.integration_center_dialog.5b38079188")
                + UiText.text("ui.java.integration_center_dialog.bb3d3745eb"));
        intro.setWrapText(true);
        VBox box = section(intro, table, new HBox(8, install, enable, disable, remove, refresh),
                new HBox(8, inspectMetadata), new Label(UiText.text("ui.java.integration_center_dialog.30ef1dd83a")), details);
        VBox.setVgrow(table, Priority.ALWAYS);
        return box;
    }


    private void inspectLocalMetadata(Window owner) {
        if (!localMetadata.hasExtractors()) {
            dialogs.showInfo(UiText.text("ui.java.integration_center_dialog.5422977aa8"), UiText.text("ui.java.integration_center_dialog.37050aa944"));
            return;
        }
        File file = fileChooserService.chooseFile(owner,
                UiText.text("ui.java.integration_center_dialog.aa26667fc9"), List.of());
        if (file == null) return;
        background.submit(() -> localMetadata.extract(file.toPath())).whenComplete((results, error) -> Platform.runLater(() -> {
            if (error != null) {
                dialogs.showError(UiText.text("ui.java.integration_center_dialog.5422977aa8"), UiText.text("ui.java.integration_center_dialog.8d1ef95dda") + rootMessage(error));
                return;
            }
            if (results == null || results.isEmpty()) {
                dialogs.showInfo(UiText.text("ui.java.integration_center_dialog.5422977aa8"), UiText.text("ui.java.integration_center_dialog.d9ac9f42d3"));
                return;
            }
            StringBuilder text = new StringBuilder();
            for (LocalMetadataExtractionService.Extraction result : results) {
                if (!text.isEmpty()) text.append("\n\n");
                text.append(UiText.text("ui.java.integration_center_dialog.4d5df77491")).append(result.providerId()).append('\n');
                if (result.values().isEmpty()) {
                    text.append(UiText.text("ui.metadata.not_found"));
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
            DialogLocalization.prepare(dialog);
            if (owner != null) dialog.initOwner(owner);
            dialog.setTitle(UiText.text("ui.java.integration_center_dialog.5422977aa8"));
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
        apiKey.setPromptText(config.apiKeyStored() ? UiText.text("ui.java.integration_center_dialog.e8b8b8b791") : UiText.text("ui.java.integration_center_dialog.ca3dea3bce"));
        Label keyState = new Label(config.apiKeyStored() ? UiText.text("ui.java.integration_center_dialog.8d360d1765") : UiText.text("ui.java.integration_center_dialog.0cb64f0d8e"));
        Label secure = new Label(config.secureStoreAvailable()
                ? UiText.text("ui.java.integration_center_dialog.c3f7718ea3") + config.secureStoreBackend()
                : UiText.text("ui.java.integration_center_dialog.aa5bc7e998"));
        secure.setWrapText(true);

        Button save = new Button(UiText.text("ui.java.integration_center_dialog.b6e5532088"));
        Button clearKey = new Button(UiText.text("ui.java.integration_center_dialog.77cc759b60"));
        save.setOnAction(e -> {
            IntegrationBackend.Result result = backend.saveAiConfiguration(endpoint.getText(), model.getText(), maxTokens.getValue(), apiKey.getText());
            apiKey.clear();
            showResult(UiText.text("ui.java.integration_center_dialog.fdc40cc5d5"), result);
            IntegrationBackend.AiConfiguration now = backend.aiConfiguration();
            keyState.setText(now.apiKeyStored() ? UiText.text("ui.java.integration_center_dialog.8d360d1765") : UiText.text("ui.java.integration_center_dialog.0cb64f0d8e"));
        });
        clearKey.setOnAction(e -> {
            showResult(UiText.text("ui.java.integration_center_dialog.fdc40cc5d5"), backend.clearAiApiKey());
            keyState.setText(UiText.text("ui.java.integration_center_dialog.0cb64f0d8e"));
        });

        GridPane form = form();
        form.add(new Label(UiText.text("ui.java.integration_center_dialog.56e0a53215")), 0, 0); form.add(endpoint, 1, 0);
        form.add(new Label(UiText.text("ui.java.integration_center_dialog.f1f1eed62c")), 0, 1); form.add(model, 1, 1);
        form.add(new Label(UiText.text("ui.java.integration_center_dialog.52b040e499")), 0, 2); form.add(maxTokens, 1, 2);
        form.add(new Label(UiText.text("ui.java.integration_center_dialog.ca3dea3bce")), 0, 3); form.add(apiKey, 1, 3);
        GridPane.setHgrow(endpoint, Priority.ALWAYS); GridPane.setHgrow(model, Priority.ALWAYS); GridPane.setHgrow(apiKey, Priority.ALWAYS);

        ListView<IntegrationBackend.AiProviderInfo> providers = new ListView<>();
        providers.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(IntegrationBackend.AiProviderInfo item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName());
            }
        });
        providers.getItems().setAll(backend.aiProviders());
        providers.setPrefHeight(150);
        TextArea providerDetails = new TextArea();
        providerDetails.setEditable(false); providerDetails.setWrapText(true); providerDetails.setPrefRowCount(5);
        providers.getSelectionModel().selectedItemProperty().addListener((o,a,b) -> providerDetails.setText(aiProviderDetails(b)));
        if (!providers.getItems().isEmpty()) providers.getSelectionModel().selectFirst();

        Button setSecrets = new Button(UiText.text("ui.java.integration_center_dialog.ac6510e8cb"));
        Button deleteSecret = new Button(UiText.text("ui.java.integration_center_dialog.00e93b7caf"));
        setSecrets.setOnAction(e -> configureProviderSecrets(owner, providers.getSelectionModel().getSelectedItem()));
        deleteSecret.setOnAction(e -> deleteProviderSecret(providers.getSelectionModel().getSelectedItem()));

        Label privacy = new Label(UiText.text("ui.java.integration_center_dialog.75ca195029")
                + UiText.text("ui.java.integration_center_dialog.e6d7a0a0fc"));
        privacy.setWrapText(true);

        VBox box = section(privacy, new Label(UiText.text("ui.java.integration_center_dialog.473d237cf9")), form, new HBox(8, save, clearKey), keyState, secure,
                new Separator(), new Label(UiText.text("ui.java.integration_center_dialog.cffd578567")), providers, providerDetails, new HBox(8, setSecrets, deleteSecret));
        return scroll(box);
    }

    private void configureProviderSecrets(Window owner, IntegrationBackend.AiProviderInfo provider) {
        if (provider == null) { dialogs.showWarning(UiText.text("ui.java.integration_center_dialog.fdc40cc5d5"), UiText.text("ui.java.integration_center_dialog.f3fd45dcb4")); return; }
        if (provider.requiredSecrets().isEmpty()) { dialogs.showInfo(UiText.text("ui.java.integration_center_dialog.fdc40cc5d5"), UiText.text("ui.java.integration_center_dialog.69956b2fae")); return; }
        if (!backend.aiConfiguration().secureStoreAvailable()) { dialogs.showError(UiText.text("ui.java.integration_center_dialog.fdc40cc5d5"), UiText.text("ui.java.integration_center_dialog.31f9b591b3")); return; }

        Dialog<ButtonType> dialog = new Dialog<>();
        DialogLocalization.prepare(dialog);
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(UiText.text("ui.java.integration_center_dialog.5eb5726ca3"));
        dialog.setHeaderText(provider.displayName());
        ButtonType save = new ButtonType(UiText.text("common.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);
        GridPane grid = form();
        List<SecretEditor> fields = new ArrayList<>();
        int row = 0;
        for (String secret : provider.requiredSecrets()) {
            PasswordField field = new PasswordField();
            field.setPromptText(UiText.text("ui.java.integration_center_dialog.3982eae417"));
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
            if (!result.success()) { showResult(UiText.text("ui.java.integration_center_dialog.fdc40cc5d5"), result); return; }
        }
        dialogs.showInfo(UiText.text("ui.java.integration_center_dialog.fdc40cc5d5"), UiText.text("ui.java.integration_center_dialog.b1d68a978d"));
    }

    private void deleteProviderSecret(IntegrationBackend.AiProviderInfo provider) {
        if (provider == null) { dialogs.showWarning(UiText.text("ui.java.integration_center_dialog.fdc40cc5d5"), UiText.text("ui.java.integration_center_dialog.f3fd45dcb4")); return; }
        if (provider.requiredSecrets().isEmpty()) { dialogs.showInfo(UiText.text("ui.java.integration_center_dialog.fdc40cc5d5"), UiText.text("ui.java.integration_center_dialog.69956b2fae")); return; }
        Optional<String> selected = dialogs.showChoiceDialog(provider.requiredSecrets(), provider.requiredSecrets().getFirst(),
                UiText.text("ui.java.integration_center_dialog.fd1f46e3b0"), provider.displayName(), UiText.text("ui.java.integration_center_dialog.76167f3768"));
        selected.ifPresent(name -> showResult(UiText.text("ui.java.integration_center_dialog.fdc40cc5d5"), backend.deleteAiProviderSecret(provider.id(), name)));
    }

    private IntegrationBackend.PluginInfo requirePlugin(TableView<IntegrationBackend.PluginInfo> table) {
        IntegrationBackend.PluginInfo selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) dialogs.showWarning(UiText.text("ui.java.integration_center_dialog.ccecfa5e49"), UiText.text("ui.java.integration_center_dialog.67b860bb0f"));
        return selected;
    }

    private String pluginDetails(IntegrationBackend.PluginInfo p) {
        if (p == null) return "";
        String services = p.services().isEmpty() ? UiText.text("ui.java.integration_center_dialog.d382957ff0") : p.services().stream().map(this::serviceName).reduce((a,b) -> a + ", " + b).orElse("");
        String permissions = p.permissions().isEmpty() ? UiText.text("ui.java.integration_center_dialog.d382957ff0") : p.permissions().stream().map(this::permissionName).reduce((a,b) -> a + ", " + b).orElse("");
        String failure = "";
        if (!p.failure().isBlank()) {
            log.warn("Plugin {} diagnostic failure: {}", p.pluginId(), p.failure());
            failure = UiText.text("ui.java.integration_center_dialog.eb1f6648df")
                    + UiText.text("common.error.operation_failed")
                    + " " + UiText.text("common.error.details_in_log");
        }
        return UiText.text("ui.java.integration_center_dialog.58f67dfa2b") + p.displayName() + UiText.text("ui.java.integration_center_dialog.79157266a1") + p.version()
                + UiText.text("ui.java.integration_center_dialog.cbf6d7509f") + pluginState(p.state()) + UiText.text("ui.java.integration_center_dialog.1f832c2f02") + trustName(p.trust())
                + UiText.text("ui.java.integration_center_dialog.2d931fe71d") + services + UiText.text("ui.java.integration_center_dialog.0274dde603") + permissions
                + UiText.text("ui.java.integration_center_dialog.bf9f97a3ce") + pluginSource(p) + failure;
    }

    private String pluginSource(IntegrationBackend.PluginInfo plugin) {
        if (plugin == null) return "";
        if (!plugin.installedPackage() || "BUNDLED".equalsIgnoreCase(plugin.source())) {
            return UiText.text("ui.integration.plugin.source.bundled");
        }
        return UiText.text("ui.integration.plugin.source.installed");
    }

    private String aiProviderDetails(IntegrationBackend.AiProviderInfo p) {
        if (p == null) return "";
        String operations = p.operations().stream().map(this::aiOperationName).reduce((a,b) -> a + ", " + b).orElse(UiText.text("ui.java.integration_center_dialog.d382957ff0"));
        String secrets = p.requiredSecrets().isEmpty() ? UiText.text("ui.java.integration_center_dialog.a9a4e11132") : String.join(", ", p.requiredSecrets());
        return UiText.text("ui.java.integration_center_dialog.b69b3c715c") + operations + UiText.text("ui.java.integration_center_dialog.206462b35f") + (p.networkRequired() ? UiText.text("ui.java.integration_center_dialog.a6c1de6be2") : UiText.text("ui.java.integration_center_dialog.1b29cc7683"))
                + UiText.text("ui.java.integration_center_dialog.65ac5e95bf") + secrets;
    }

    private String pluginState(String raw) {
        return switch (upper(raw)) {
            case "ENABLED" -> UiText.text("ui.java.integration_center_dialog.beba41b98c");
            case "DISABLED" -> UiText.text("ui.java.integration_center_dialog.ca6de284d4");
            case "QUARANTINED" -> UiText.text("ui.java.integration_center_dialog.dcaa6cd158");
            case "FAILED" -> UiText.text("common.error");
            default -> raw == null ? "" : raw;
        };
    }

    private String trustName(String raw) {
        return switch (upper(raw)) {
            case "TRUSTED" -> UiText.text("ui.java.integration_center_dialog.ad5afa4796");
            case "UNTRUSTED" -> UiText.text("ui.java.integration_center_dialog.4dd43ce291");
            default -> raw == null ? "" : raw;
        };
    }

    private String permissionName(String raw) {
        return switch (upper(raw)) {
            case "NETWORK_ACCESS" -> UiText.text("ui.java.integration_center_dialog.a710bfbcdf");
            case "FILESYSTEM_READ" -> UiText.text("ui.java.integration_center_dialog.01d87604f8");
            case "FILESYSTEM_WRITE" -> UiText.text("ui.java.integration_center_dialog.d6eeb350d5");
            case "EXTERNAL_PROCESS_EXECUTION" -> UiText.text("ui.java.integration_center_dialog.73d2e43f36");
            default -> raw == null ? "" : raw;
        };
    }

    private String serviceName(String raw) {
        return switch (upper(raw)) {
            case "METADATA_PROVIDER" -> UiText.text("ui.java.integration_center_dialog.2fa0a6f556");
            case "COVER_PROVIDER" -> UiText.text("ui.java.integration_center_dialog.5d1b43999c");
            case "BOOK_IMPORTER" -> UiText.text("ui.java.integration_center_dialog.44f5e2b122");
            case "BOOK_CONVERTER" -> UiText.text("ui.java.integration_center_dialog.39672183ca");
            case "METADATA_EXTRACTOR" -> UiText.text("ui.java.integration_center_dialog.0789be3f00");
            case "CONTENT_EXTRACTOR" -> UiText.text("ui.java.integration_center_dialog.089df50b8d");
            case "EXPORT_PROVIDER" -> UiText.text("ui.java.integration_center_dialog.f1fc5d2b24");
            case "TRANSLATION_PROVIDER" -> UiText.text("ui.java.integration_center_dialog.fb8a32bc8a");
            case "DICTIONARY_PROVIDER" -> UiText.text("ui.java.integration_center_dialog.fc9f6db98d");
            case "DEVICE_PROVIDER" -> UiText.text("ui.java.integration_center_dialog.bdec5cb53d");
            case "AI_PROVIDER" -> UiText.text("ui.java.integration_center_dialog.0ef0e3422d");
            default -> raw == null ? "" : raw;
        };
    }

    private String aiOperationName(String raw) {
        return switch (upper(raw)) {
            case "SUMMARY" -> UiText.text("ui.java.integration_center_dialog.3fec5bba10");
            case "QUESTION_ANSWER" -> UiText.text("ui.java.integration_center_dialog.fd29a6d7c0");
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
        String message = renderMessage(result.userMessage());
        if (result.success()) dialogs.showInfo(title, message);
        else dialogs.showError(title, message);
    }

    private String renderMessage(IntegrationBackend.UserMessage message) {
        if (message == null) return UiText.text("common.error.operation_failed");
        if (!message.technicalDetail().isBlank()) {
            log.warn("Integration operation detail for {}: {}", message.key(), message.technicalDetail());
        }
        String rendered = UiText.format(message.key(), message.arguments().toArray());
        if (!message.technicalDetail().isBlank()) {
            rendered += "\n\n" + UiText.text("common.error.details_in_log");
        }
        return rendered;
    }

    private void copyText(String value, String successMessage) {
        if (value == null || value.isBlank()) { dialogs.showWarning(UiText.text("ui.java.integration_center_dialog.85eeb25554"), UiText.text("ui.java.integration_center_dialog.f2e881cde4")); return; }
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        Clipboard.getSystemClipboard().setContent(content);
        dialogs.showInfo(UiText.text("ui.java.integration_center_dialog.85eeb25554"), successMessage);
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
        return UiExceptionSupport.message(error);
    }

    private record SecretEditor(String name, PasswordField field) { }
}
