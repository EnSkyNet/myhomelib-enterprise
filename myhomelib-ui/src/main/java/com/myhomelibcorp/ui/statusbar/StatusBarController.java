package com.myhomelibcorp.ui.statusbar;

import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.StatusBarViewModel;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StatusBarController {

    private final ApplicationState appState;

    @FXML private Label statusLabel;
    @FXML private Label statsLabel;
    @FXML private ProgressBar progressBar;

    @FXML
    public void initialize() {
        StatusBarViewModel vm = appState.getStatusBar();
        statusLabel.textProperty().bind(vm.statusTextProperty());
        progressBar.progressProperty().bind(vm.progressProperty());
        progressBar.visibleProperty().bind(vm.progressVisibleProperty());

        // Оновлення статистики при зміні
        vm.statisticsProperty().addListener((obs, old, stats) -> {
            if (stats != null) {
                statsLabel.setText(String.format("Книг: %d | Авторів: %d | Серій: %d",
                        stats.getBooksCount(), stats.getAuthorsCount(), stats.getSeriesCount()));
            }
        });
    }
}