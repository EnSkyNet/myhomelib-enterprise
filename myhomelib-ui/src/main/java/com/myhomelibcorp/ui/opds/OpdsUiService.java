package com.myhomelibcorp.ui.opds;

import com.myhomelibcorp.application.opds.OpdsServerControl;
import com.myhomelibcorp.application.opds.OpdsServerSettings;
import com.myhomelibcorp.application.opds.OpdsServerStatus;
import com.myhomelibcorp.application.opds.OpdsSettingsService;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/** Stage 18 lifecycle/settings UI; the JavaFX layer knows only the application control interface. */
@Component
@RequiredArgsConstructor
public class OpdsUiService {
    private final OpdsServerControl serverControl;
    private final OpdsSettingsService settingsService;

    public void show(Window owner) {
        OpdsServerSettings saved = settingsService.load();
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("OPDS сервер");
        if (owner != null) dialog.initOwner(owner);

        TextField bind = new TextField(saved.bindAddress());
        bind.setPromptText("127.0.0.1");
        Spinner<Integer> port = new Spinner<>(1, 65535, saved.port());
        port.setEditable(true);
        CheckBox auth = new CheckBox("Basic authentication");
        auth.setSelected(saved.basicAuthEnabled());
        TextField username = new TextField(saved.username());
        PasswordField password = new PasswordField();
        password.setText(saved.password());
        CheckBox autostart = new CheckBox("Запускати OPDS разом із програмою");
        autostart.setSelected(saved.autostart());
        Label status = new Label();
        status.setWrapText(true);
        Label exposure = new Label();
        exposure.setWrapText(true);
        exposure.getStyleClass().add("danger-text");

        Runnable updateAuth = () -> {
            username.setDisable(!auth.isSelected());
            password.setDisable(!auth.isSelected());
        };
        auth.selectedProperty().addListener((obs, old, value) -> updateAuth.run());
        updateAuth.run();

        Runnable updateExposure = () -> exposure.setText(isLoopback(bind.getText()) ?
                "Доступ обмежено цим комп'ютером." :
                "УВАГА: адреса не є localhost. OPDS може бути доступний іншим пристроям у мережі. Увімкніть пароль та перевірте firewall.");
        bind.textProperty().addListener((obs, old, value) -> updateExposure.run());
        updateExposure.run();

        GridPane form = new GridPane();
        form.setHgap(10); form.setVgap(8);
        form.addRow(0, new Label("Bind address:"), bind);
        form.addRow(1, new Label("Port:"), port);
        form.add(auth, 0, 2, 2, 1);
        form.addRow(3, new Label("Користувач:"), username);
        form.addRow(4, new Label("Пароль:"), password);
        form.add(autostart, 0, 5, 2, 1);

        Button save = new Button("Зберегти");
        Button start = new Button("Запустити");
        Button stop = new Button("Зупинити");
        Button refresh = new Button("Оновити статус");
        HBox actions = new HBox(8, save, start, stop, refresh);

        Runnable refreshStatus = () -> renderStatus(serverControl.status(), status, exposure, bind);
        save.setOnAction(e -> {
            try {
                OpdsServerSettings value = settings(bind, port, auth, username, password, autostart);
                settingsService.save(value);
                status.setText("Налаштування збережено.");
                updateExposure.run();
            } catch (RuntimeException ex) {
                status.setText("Некоректні налаштування: " + ex.getMessage());
            }
        });
        start.setOnAction(e -> {
            try {
                OpdsServerSettings value = settings(bind, port, auth, username, password, autostart);
                if (value.basicAuthEnabled() && (value.username().isBlank() || value.password().isBlank())) {
                    status.setText("Для Basic authentication задайте користувача і пароль.");
                    return;
                }
                settingsService.save(value);
                renderStatus(serverControl.start(value), status, exposure, bind);
            } catch (RuntimeException ex) {
                status.setText("Не вдалося запустити: " + ex.getMessage());
            }
        });
        stop.setOnAction(e -> { serverControl.stop(); refreshStatus.run(); });
        refresh.setOnAction(e -> refreshStatus.run());

        VBox root = new VBox(12, form, exposure, new Separator(), status, actions);
        root.setPadding(new Insets(14));
        root.setPrefWidth(570);
        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Node close = dialog.getDialogPane().lookupButton(ButtonType.CLOSE);
        if (close != null) ((Button) close).setText("Закрити");
        refreshStatus.run();
        dialog.showAndWait();
    }

    private static OpdsServerSettings settings(TextField bind, Spinner<Integer> port, CheckBox auth,
                                               TextField username, PasswordField password, CheckBox autostart) {
        int selectedPort = port.getValueFactory().getValue();
        return new OpdsServerSettings(bind.getText(), selectedPort, auth.isSelected(),
                username.getText(), password.getText(), autostart.isSelected());
    }

    private static void renderStatus(OpdsServerStatus server, Label status, Label exposure, TextField bind) {
        if (server.running()) {
            status.setText("Працює: " + server.baseUrl() + "\nHealth: http://" + displayHost(server.bindAddress()) + ":" + server.port() + "/health");
            if (server.exposedBeyondLocalhost()) {
                exposure.setText("УВАГА: OPDS слухає не лише localhost. Перевірте Basic authentication та firewall.");
            }
        } else {
            status.setText(server.message());
            exposure.setText(isLoopback(bind.getText()) ? "Доступ обмежено цим комп'ютером." :
                    "УВАГА: після запуску OPDS може бути доступний у мережі.");
        }
    }

    private static String displayHost(String host) {
        return host != null && host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private static boolean isLoopback(String host) {
        try { return InetAddress.getByName(host == null || host.isBlank() ? "127.0.0.1" : host.trim()).isLoopbackAddress(); }
        catch (Exception e) { return false; }
    }
}
