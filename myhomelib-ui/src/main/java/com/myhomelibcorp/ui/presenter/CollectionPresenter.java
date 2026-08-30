package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.dto.CollectionDto;
import com.myhomelibcorp.application.dto.CreateCollectionRequest;
import com.myhomelibcorp.application.service.CollectionManagementService;
import com.myhomelibcorp.application.usecase.collection.CreateCollectionUseCase;
import com.myhomelibcorp.application.usecase.collection.LoadCollectionsUseCase;
import com.myhomelibcorp.application.usecase.collection.SwitchCollectionUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FileChooserService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.collections.ObservableList;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class CollectionPresenter {

    private final LoadCollectionsUseCase loadCollectionsUseCase;
    private final CreateCollectionUseCase createCollectionUseCase;
    private final DialogService dialogService;
    private final FileChooserService fileChooserService;
    private final CollectionManagementService collectionManagementService;
    private final SwitchCollectionUseCase switchCollectionUseCase;
    private final ApplicationState appState;

    public void showCreateCollectionDialog(ObservableList<CollectionDto> collectionList, Stage owner) {
        Optional<String> nameResult = dialogService.showTextInput("Створити колекцію",
                "Введіть назву нової колекції", "Назва:", "");
        if (nameResult.isEmpty() || nameResult.get().isBlank()) {
            return;
        }
        String name = nameResult.get();

        File dbFile = fileChooserService.chooseFileToSave(owner,
                "Виберіть місце для бази даних колекції",
                name + ".db");
        String dbFilePath = dbFile != null ? dbFile.getAbsolutePath() : null;

        if (dbFilePath == null) {
            String defaultPath = System.getProperty("user.home") + "/.myhomelibcorp/libraries/" +
                    UUID.randomUUID() + ".db";
            dbFilePath = defaultPath;
            log.info("Використовуємо стандартний шлях для БД: {}", dbFilePath);
        }

        try {
            CreateCollectionRequest request = CreateCollectionRequest.builder()
                    .name(name)
                    .dbFile(Paths.get(dbFilePath))
                    .importOnCreate(true)
                    .createIndex(true)
                    .build();

            Collection collection = createCollectionUseCase.execute(request);

            appState.getStatusBar().setStatusText("Колекцію '" + name + "' створено");

            Collection activated = switchCollectionUseCase.execute(collection);
            appState.setCurrentLibraryCollection(activated);
            if (collectionList != null) {
                loadCollections(collectionList);
            }

            appState.getStatusBar().setStatusText("Переключено на колекцію: " + activated.getName());

        } catch (Exception e) {
            dialogService.showError("Помилка", e.getMessage());
            log.error("Помилка створення колекції", e);
        }
    }



    public void loadCollections(ObservableList<CollectionDto> collectionList) {
        try {
            var collections = loadCollectionsUseCase.execute();
            collectionList.setAll(collections);
        } catch (Exception e) {
            log.error("Помилка завантаження колекцій", e);
            dialogService.showError("Помилка", "Не вдалося завантажити колекції: " + e.getMessage());
        }
    }

    public boolean hasActiveCollection() {
        return collectionManagementService.hasActiveCollection();
    }

    public boolean isCollectionReady() {
        return collectionManagementService.isCollectionReady();
    }

    public long getDatabaseSize() {
        return collectionManagementService.getDatabaseSize();
    }

    public void closeCurrentCollection() {
        collectionManagementService.closeCurrentCollection();
    }

    public Collection getCurrentCollection() {
        return collectionManagementService.getCurrentCollection();
    }


}