package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.ui.presenter.BookImportPresenter;
import com.myhomelibcorp.ui.presenter.CollectionPresenter;
import com.myhomelibcorp.ui.presenter.GroupPresenter;
import com.myhomelibcorp.ui.presenter.RefreshPresenter;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
@RequiredArgsConstructor
@Slf4j
public class MainController {

    private final ApplicationState appState;
    private final BookImportPresenter bookImportPresenter;
    private final CollectionPresenter collectionPresenter;
    private final GroupPresenter groupPresenter;
    private final RefreshPresenter refreshPresenter;
    private final DialogService dialogService;

    @FXML private BorderPane mainPane;
    @FXML private TableView<?> bookTableView;

    @FXML
    public void initialize() {
        // Прив'язка таблиці до ViewModel
        // bookTableView.setItems(appState.getBookTable().getBooks());
        log.info("MainController ініціалізовано");
    }

    @FXML
    public void handleImportFb2() {
        bookImportPresenter.importFb2();
    }

    @FXML
    public void handleImportInpx() {
        bookImportPresenter.importInpx();
    }

    @FXML
    public void handleImportDirectory() {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Виберіть каталог з книгами");
        File dir = directoryChooser.showDialog(null);
        if (dir != null) {
            bookImportPresenter.importDirectory(dir.toPath());
        }
    }

    @FXML
    public void handleRefresh() {
        refreshPresenter.refreshAll();
    }

    @FXML
    public void handleExit() {
        Platform.exit();
    }

    @FXML
    public void handleAbout() {
        dialogService.showInfo("Про програму", "MyHomeLib Enterprise",
                "Версія 1.0.0-SNAPSHOT\nJava 21, Spring Boot 3.5, JavaFX 21");
    }

    @FXML
    public void handleNewCollection() {
        Stage stage = (Stage) mainPane.getScene().getWindow();
        collectionPresenter.showCreateCollectionDialog(
                javafx.collections.FXCollections.observableArrayList(), stage);
    }

    @FXML
    public void handleRenameCollection() {
        // потребує вибору колекції
    }

    @FXML
    public void handleDeleteCollection() {
        // потребує вибору колекції
    }

    @FXML
    public void handleSelectCollection() {
        // потребує вибору колекції
    }

    @FXML
    public void handleAddGroup() {
        // виклик groupPresenter
    }

    @FXML
    public void handleEditGroup() {
        // виклик groupPresenter
    }

    @FXML
    public void handleDeleteGroup() {
        // виклик groupPresenter
    }

    @FXML
    public void handleEditMetadata() {
        dialogService.showInfo("Інформація", "Редагування метаданих", "Функція поки що не реалізована");
    }

    @FXML
    public void handleDeleteBook() {
        dialogService.showInfo("Інформація", "Видалення книги", "Функція поки що не реалізована");
    }

    @FXML
    public void handleShowColumns() {
        dialogService.showInfo("Налаштування", "Відображення колонок", "Функція поки що не реалізована");
    }

    @FXML
    public void handleExport() {
        dialogService.showInfo("Інформація", "Експорт", "Функція поки що не реалізована");
    }

    @FXML
    public void handleRebuildIndex() {
        dialogService.showInfo("Інформація", "Перебудова індексу", "Функція поки що не реалізована");
    }
}