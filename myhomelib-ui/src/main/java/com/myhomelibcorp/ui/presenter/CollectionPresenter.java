package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.collection.CollectionUiCommitGuard;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.operation.OperationKind;
import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.application.progress.OperationStage;

import com.myhomelibcorp.ui.service.UiText;
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
    private final CollectionUiCommitGuard collectionUiCommitGuard;
    private final OperationCenterService operationCenter;

    public void showCreateCollectionDialog(ObservableList<CollectionDto> collectionList, Stage owner) {
        Optional<String> nameResult = dialogService.showTextInput(UiText.text("ui.java.collection_presenter.365d3d5a7f"),
                UiText.text("ui.java.collection_presenter.1dc64220a2"), UiText.text("common.name.label"), "");
        if (nameResult.isEmpty() || nameResult.get().isBlank()) {
            return;
        }
        String name = nameResult.get();

        File dbFile = fileChooserService.chooseFileToSave(owner,
                UiText.text("ui.java.collection_presenter.bcf5fcd230"),
                name + ".db");
        String dbFilePath = dbFile != null ? dbFile.getAbsolutePath() : null;

        if (dbFilePath == null) {
            String defaultPath = System.getProperty("user.home") + "/.myhomelibcorp/libraries/" +
                    UUID.randomUUID() + ".db";
            dbFilePath = defaultPath;
            log.info("Використовуємо стандартний шлях для БД: {}", dbFilePath);
        }

        String operationTitle = UiText.text("ui.java.collection_workspace_controller.9dce38545e") + name;
        String operationId = operationCenter.start(operationTitle, "", OperationKind.COLLECTION_CREATE,
                OperationStage.CREATING_COLLECTION, false);
        try {
            CreateCollectionRequest request = CreateCollectionRequest.builder()
                    .name(name)
                    .dbFile(Paths.get(dbFilePath))
                    .importOnCreate(true)
                    .createIndex(true)
                    .build();

            Collection collection = createCollectionUseCase.execute(request);

            appState.getStatusBar().setStatusText(UiText.text("ui.java.collection_presenter.581a96f07f") + name + UiText.text("ui.java.collection_presenter.eccde05e49"));

            operationCenter.accept(operationTitle, collection.getId(), OperationKind.COLLECTION_CREATE,
                    OperationProgress.stage(operationId, OperationStage.FINALIZING, false));
            Collection activated = switchCollectionUseCase.execute(collection);
            boolean committed = collectionUiCommitGuard.runIfStillActive(activated.getId(), () -> {
                appState.setCurrentLibraryCollection(activated);
                if (collectionList != null) loadCollections(collectionList);
                appState.getStatusBar().setStatusText(UiText.text("ui.java.collection_presenter.c3d5ffb6d9") + activated.getName());
            });
            if (!committed && collectionList != null) loadCollections(collectionList);
            operationCenter.complete(operationId, UiText.text("ui.java.collection_workspace_controller.1e198f406d") + activated.getName());

        } catch (Exception e) {
            operationCenter.fail(operationId, e);
            dialogService.showError(UiText.text("common.error"), UiExceptionSupport.message(e));
            log.error("Помилка створення колекції", e);
        }
    }



    public void loadCollections(ObservableList<CollectionDto> collectionList) {
        try {
            var collections = loadCollectionsUseCase.execute();
            collectionList.setAll(collections);
        } catch (Exception e) {
            log.error("Помилка завантаження колекцій", e);
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.collection_presenter.6462ec753f") + UiExceptionSupport.message(e));
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

    public Collection getCurrentCollection() {
        return collectionManagementService.getCurrentCollection();
    }


}