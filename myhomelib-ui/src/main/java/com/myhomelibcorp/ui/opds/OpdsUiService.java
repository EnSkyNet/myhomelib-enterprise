package com.myhomelibcorp.ui.opds;

import com.myhomelibcorp.application.opds.OpdsCertificateInfo;
import com.myhomelibcorp.application.opds.OpdsCertificateManager;
import com.myhomelibcorp.application.opds.OpdsServerControl;
import com.myhomelibcorp.application.opds.OpdsServerSettings;
import com.myhomelibcorp.application.opds.OpdsServerStatus;
import com.myhomelibcorp.application.opds.OpdsSettingsService;
import com.myhomelibcorp.application.opds.OpdsTlsSettings;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.service.LocalizationService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** OPDS lifecycle/settings UI backed only by application-level control interfaces. */
@Component
@RequiredArgsConstructor
public class OpdsUiService {
    private static final DateTimeFormatter CERT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final OpdsServerControl serverControl;
    private final OpdsSettingsService settingsService;
    private final OpdsCertificateManager certificateManager;
    private final UiBackgroundExecutor backgroundExecutor;
    private final LocalizationService i18n;

    public void show(Window owner) {
        OpdsServerSettings saved = settingsService.load();
        AtomicReference<OpdsTlsSettings> tlsState = new AtomicReference<>(saved.tls());
        AtomicReference<OpdsCertificateInfo> certificateState = new AtomicReference<>();

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(i18n.text("ui.opds.title"));
        if (owner != null) dialog.initOwner(owner);

        TextField bind = new TextField(saved.bindAddress());
        bind.setPromptText("127.0.0.1");
        Spinner<Integer> port = new Spinner<>(1, 65535, saved.port());
        port.setEditable(true);
        CheckBox auth = new CheckBox("Basic authentication");
        auth.setSelected(saved.basicAuthEnabled());
        TextField username = new TextField(saved.username());
        PasswordField password = new PasswordField();
        if (settingsService.hasStoredPassword()) {
            password.setPromptText(i18n.text("ui.opds.password.saved_prompt"));
        }
        CheckBox autostart = new CheckBox(i18n.text("ui.opds.autostart"));
        autostart.setSelected(saved.autostart());

        CheckBox tlsEnabled = new CheckBox("HTTPS / TLS");
        tlsEnabled.setSelected(saved.tls().enabled());
        TextField keyStorePath = new TextField(saved.tls().keyStorePath());
        keyStorePath.setEditable(false);
        keyStorePath.setPromptText(i18n.text("ui.opds.certificate.not_configured_prompt"));
        TextField fingerprint = new TextField();
        fingerprint.setEditable(false);
        fingerprint.setPromptText(i18n.text("ui.opds.fingerprint.prompt"));
        Label certificateDetails = new Label();
        certificateDetails.setWrapText(true);
        Label trustWarning = new Label();
        trustWarning.setWrapText(true);
        trustWarning.getStyleClass().add("danger-text");

        Label status = new Label();
        status.setWrapText(true);
        Label exposure = new Label();
        exposure.setWrapText(true);
        exposure.getStyleClass().add("danger-text");

        Button generate = new Button(i18n.text("ui.opds.certificate.generate"));
        Button importPem = new Button(i18n.text("ui.opds.certificate.import"));
        Button regenerate = new Button(i18n.text("ui.opds.certificate.regenerate"));
        HBox certificateActions = new HBox(8, generate, importPem, regenerate);

        Runnable updateAuth = () -> {
            username.setDisable(!auth.isSelected());
            password.setDisable(!auth.isSelected());
        };
        auth.selectedProperty().addListener((obs, old, value) -> updateAuth.run());
        updateAuth.run();

        Runnable updateExposure = () -> exposure.setText(isLoopback(bind.getText()) ?
                (tlsEnabled.isSelected()
                        ? i18n.text("ui.opds.exposure.local_https")
                        : i18n.text("ui.opds.exposure.local_http")) :
                (tlsEnabled.isSelected()
                        ? i18n.text("ui.opds.exposure.lan_https")
                        : i18n.text("ui.opds.exposure.lan_blocked")));
        bind.textProperty().addListener((obs, old, value) -> updateExposure.run());
        tlsEnabled.selectedProperty().addListener((obs, old, value) -> updateExposure.run());
        updateExposure.run();

        Runnable renderCertificate = () -> {
            OpdsTlsSettings tls = tlsState.get();
            keyStorePath.setText(tls == null ? "" : tls.keyStorePath());
            OpdsCertificateInfo info = certificateState.get();
            if (info == null) {
                fingerprint.clear();
                certificateDetails.setText(tls != null && tls.hasKeyStorePath()
                        ? i18n.text("ui.opds.certificate.inspect_failed")
                        : i18n.text("ui.opds.certificate.not_configured"));
                trustWarning.setText(tlsEnabled.isSelected()
                        ? i18n.text("ui.opds.certificate.required_for_https")
                        : "");
                regenerate.setDisable(true);
                return;
            }
            fingerprint.setText(info.fingerprintSha256());
            certificateDetails.setText(i18n.format("ui.opds.certificate.details", info.subject(),
                    CERT_DATE.format(info.notBefore()), CERT_DATE.format(info.notAfter())));
            trustWarning.setText(info.selfSigned()
                    ? i18n.text("ui.opds.certificate.trust.self_signed")
                    : i18n.text("ui.opds.certificate.trust.imported"));
            regenerate.setDisable(false);
        };

        try {
            Optional<OpdsCertificateInfo> existing = certificateManager.inspect(saved.tls());
            existing.ifPresent(certificateState::set);
        } catch (RuntimeException e) {
            status.setText(i18n.format("ui.opds.status.certificate_read_error", safeMessage(e)));
        }
        renderCertificate.run();

        Runnable setCertificateBusy = () -> {
            generate.setDisable(true);
            importPem.setDisable(true);
            regenerate.setDisable(true);
        };
        Runnable clearCertificateBusy = () -> {
            generate.setDisable(false);
            importPem.setDisable(false);
            regenerate.setDisable(certificateState.get() == null);
        };

        generate.setOnAction(e -> runCertificateTask(
                () -> certificateManager.generateSelfSigned(bind.getText()),
                i18n.text("ui.opds.status.certificate_generating"), i18n.text("ui.opds.status.certificate_generated"),
                status, tlsEnabled, tlsState, certificateState, renderCertificate,
                setCertificateBusy, clearCertificateBusy));
        regenerate.setOnAction(e -> runCertificateTask(
                () -> certificateManager.generateSelfSigned(bind.getText()),
                i18n.text("ui.opds.status.certificate_regenerating"), i18n.text("ui.opds.status.certificate_regenerated"),
                status, tlsEnabled, tlsState, certificateState, renderCertificate,
                setCertificateBusy, clearCertificateBusy));
        importPem.setOnAction(e -> {
            Path cert = chooseFile(owner, i18n.text("ui.opds.choose_certificate.title"),
                    new FileChooser.ExtensionFilter("Certificate PEM", "*.pem", "*.crt", "*.cer"));
            if (cert == null) return;
            Path key = chooseFile(owner, i18n.text("ui.opds.choose_private_key.title"),
                    new FileChooser.ExtensionFilter("Private key PEM", "*.pem", "*.key"));
            if (key == null) return;
            runCertificateTask(
                    () -> certificateManager.importPem(cert, key),
                    i18n.text("ui.opds.status.certificate_importing"), i18n.text("ui.opds.status.certificate_imported"),
                    status, tlsEnabled, tlsState, certificateState, renderCertificate,
                    setCertificateBusy, clearCertificateBusy);
        });

        GridPane form = new GridPane();
        form.setHgap(10); form.setVgap(8);
        form.addRow(0, new Label("Bind address:"), bind);
        form.addRow(1, new Label("Port:"), port);
        form.add(auth, 0, 2, 2, 1);
        form.addRow(3, new Label(i18n.text("ui.opds.username.label")), username);
        form.addRow(4, new Label(i18n.text("ui.opds.password.label")), password);
        form.add(autostart, 0, 5, 2, 1);

        GridPane tlsForm = new GridPane();
        tlsForm.setHgap(10); tlsForm.setVgap(8);
        tlsForm.add(tlsEnabled, 0, 0, 2, 1);
        tlsForm.addRow(1, new Label("Keystore:"), keyStorePath);
        tlsForm.addRow(2, new Label("SHA-256 fingerprint:"), fingerprint);
        tlsForm.add(certificateDetails, 0, 3, 2, 1);
        tlsForm.add(trustWarning, 0, 4, 2, 1);
        tlsForm.add(certificateActions, 0, 5, 2, 1);
        keyStorePath.setPrefColumnCount(45);
        fingerprint.setPrefColumnCount(45);

        Button save = new Button(i18n.text("common.save"));
        Button start = new Button(i18n.text("ui.opds.start"));
        Button stop = new Button(i18n.text("ui.opds.stop"));
        Button refresh = new Button(i18n.text("ui.opds.refresh_status"));
        HBox actions = new HBox(8, save, start, stop, refresh);

        Runnable refreshStatus = () -> renderStatus(serverControl.status(), status, exposure, bind);
        save.setOnAction(e -> {
            try {
                OpdsServerSettings value = settings(bind, port, auth, username, password, autostart,
                        tlsEnabled, tlsState.get(), saved);
                settingsService.save(value);
                status.setText(i18n.text("ui.opds.status.settings_saved"));
                updateExposure.run();
            } catch (RuntimeException ex) {
                status.setText(i18n.format("ui.opds.status.invalid_settings", safeMessage(ex)));
            }
        });
        start.setOnAction(e -> {
            try {
                OpdsServerSettings value = settings(bind, port, auth, username, password, autostart,
                        tlsEnabled, tlsState.get(), saved);
                if (value.basicAuthEnabled() && value.username().isBlank()) {
                    status.setText(i18n.text("ui.opds.status.username_required"));
                    return;
                }
                if (value.tls().enabled() && !value.tls().hasKeyStorePath()) {
                    status.setText(i18n.text("ui.opds.status.certificate_required"));
                    return;
                }
                settingsService.save(value);
                OpdsServerSettings runtime = settingsService.load();
                if (runtime.basicAuthEnabled() && runtime.password().isBlank()) {
                    status.setText(i18n.text("ui.opds.status.password_required"));
                    return;
                }
                renderStatus(serverControl.start(runtime), status, exposure, bind);
            } catch (RuntimeException ex) {
                status.setText(i18n.format("ui.opds.status.start_error", safeMessage(ex)));
            }
        });
        stop.setOnAction(e -> { serverControl.stop(); refreshStatus.run(); });
        refresh.setOnAction(e -> refreshStatus.run());

        VBox root = new VBox(12,
                form,
                exposure,
                new Separator(),
                new Label(i18n.text("ui.opds.certificate.section")),
                tlsForm,
                new Separator(),
                status,
                actions);
        root.setPadding(new Insets(14));
        root.setPrefWidth(760);
        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Node close = dialog.getDialogPane().lookupButton(ButtonType.CLOSE);
        if (close != null) ((Button) close).setText(i18n.text("common.close"));
        refreshStatus.run();
        dialog.showAndWait();
    }

    private void runCertificateTask(Supplier<OpdsCertificateManager.ManagedCertificate> task,
                                    String busyMessage,
                                    String successMessage,
                                    Label status,
                                    CheckBox tlsEnabled,
                                    AtomicReference<OpdsTlsSettings> tlsState,
                                    AtomicReference<OpdsCertificateInfo> certificateState,
                                    Runnable renderCertificate,
                                    Runnable setBusy,
                                    Runnable clearBusy) {
        status.setText(busyMessage);
        setBusy.run();
        backgroundExecutor.submit(task::get).whenComplete((managed, error) -> Platform.runLater(() -> {
            clearBusy.run();
            if (error != null) {
                status.setText("TLS: " + safeMessage(unwrap(error)));
                return;
            }
            tlsState.set(managed.tls());
            certificateState.set(managed.certificate());
            tlsEnabled.setSelected(true);
            renderCertificate.run();
            clearBusy.run();
            status.setText(successMessage + " " + i18n.text("ui.opds.status.save_or_start_hint"));
        }));
    }

    private static Path chooseFile(Window owner, String title, FileChooser.ExtensionFilter filter) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(filter);
        var file = chooser.showOpenDialog(owner);
        return file == null ? null : file.toPath();
    }

    private static OpdsServerSettings settings(TextField bind, Spinner<Integer> port, CheckBox auth,
                                               TextField username, PasswordField password, CheckBox autostart,
                                               CheckBox tlsEnabled, OpdsTlsSettings currentTls,
                                               OpdsServerSettings preserved) {
        int selectedPort = port.getValueFactory().getValue();
        OpdsTlsSettings tls = currentTls == null ? OpdsTlsSettings.disabled() : currentTls;
        tls = new OpdsTlsSettings(tlsEnabled.isSelected(), tls.keyStorePath(), tls.keyStoreType(), tls.keyStorePassword());
        return new OpdsServerSettings(bind.getText(), selectedPort, auth.isSelected(),
                username.getText(), password.getText(), autostart.isSelected(), tls, preserved.limits());
    }

    private void renderStatus(OpdsServerStatus server, Label status, Label exposure, TextField bind) {
        if (server.running()) {
            status.setText(i18n.format("ui.opds.status.running", server.baseUrl(), server.healthUrl()));
            if (server.exposedBeyondLocalhost()) {
                exposure.setText(server.baseUrl().startsWith("https://")
                        ? i18n.text("ui.opds.exposure.running_lan_https")
                        : i18n.text("ui.opds.exposure.running_lan_blocked"));
            }
        } else {
            status.setText(server.message());
            exposure.setText(isLoopback(bind.getText()) ? i18n.text("ui.opds.exposure.local_only") :
                    i18n.text("ui.opds.exposure.network_warning"));
        }
    }

    private static boolean isLoopback(String host) {
        try { return InetAddress.getByName(host == null || host.isBlank() ? "127.0.0.1" : host.trim()).isLoopbackAddress(); }
        catch (Exception e) { return false; }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
        return current;
    }

    private String safeMessage(Throwable error) {
        if (error == null) return i18n.text("common.error.unknown");
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
