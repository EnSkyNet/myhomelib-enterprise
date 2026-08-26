package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.dto.CollectionDto;
import com.myhomelibcorp.application.dto.CreateCollectionRequest;
import com.myhomelibcorp.application.service.CollectionManagementService;
import com.myhomelibcorp.application.service.DatabaseToolsService;
import com.myhomelibcorp.application.usecase.collection.CreateCollectionUseCase;
import com.myhomelibcorp.application.usecase.collection.DeleteCollectionUseCase;
import com.myhomelibcorp.application.usecase.collection.LoadCollectionsUseCase;
import com.myhomelibcorp.application.usecase.collection.RenameCollectionUseCase;
import com.myhomelibcorp.application.usecase.series.SyncSeriesUseCase;
import com.myhomelibcorp.application.statistics.StatisticsService;
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

/**
 * Презентер для роботи з колекціями.
 * Використовує Application сервіси замість прямих залежностей від Infrastructure.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CollectionPresenter {

    private final LoadCollectionsUseCase loadCollectionsUseCase;
    private final CreateCollectionUseCase createCollectionUseCase;
    private final RenameCollectionUseCase renameCollectionUseCase;
    private final DeleteCollectionUseCase deleteCollectionUseCase;
    private final DialogService dialogService;
    private final FileChooserService fileChooserService;
    private final CollectionManagementService collectionManagementService;
    private final DatabaseToolsService databaseToolsService;
    private final ApplicationState appState;
    private final SyncSeriesUseCase syncSeriesUseCase;
    private final StatisticsService statisticsService;

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

            CollectionDto dto = CollectionDto.builder()
                    .id(collection.getId())
                    .name(collection.getName())
                    .active(false)
                    .allowRename(true)
                    .allowDelete(true)
                    .rootFolder(collection.getRootFolder() != null ? collection.getRootFolder().toString() : null)
                    .dbFile(collection.getDbFile())
                    .type(collection.getType())
                    .booksCount(-1L)
                    .build();
            collectionList.add(dto);
            appState.getStatusBar().setStatusText("Колекцію '" + name + "' створено");

            // Використовуємо CollectionManagementService замість CollectionManager
            collectionManagementService.switchToCollection(collection);

            // Оновлюємо статистику та серії після створення
            statisticsService.refreshStatistics();
            syncSeriesUseCase.execute();

            appState.getStatusBar().setStatusText("Переключено на колекцію: " + collection.getName());

        } catch (Exception e) {
            dialogService.showError("Помилка", e.getMessage());
            log.error("Помилка створення колекції", e);
        }
    }

    public void showRenameCollectionDialog(CollectionDto collection, ObservableList<CollectionDto> collectionList) {
        if (collection == null) {
            dialogService.showWarning("Помилка", "Не вибрано колекцію");
            return;
        }
        Optional<String> result = dialogService.showTextInput("Перейменувати колекцію",
                "Введіть нову назву колекції", "Назва:", collection.getName());
        result.ifPresent(newName -> {
            if (!newName.isBlank() && !newName.equals(collection.getName())) {
                try {
                    Collection renamed = renameCollectionUseCase.execute(collection.getId(), newName);
                    CollectionDto updated = CollectionDto.builder()
                            .id(renamed.getId())
                            .name(renamed.getName())
                            .active(collection.isActive())
                            .allowRename(collection.isAllowRename())
                            .allowDelete(collection.isAllowDelete())
                            .rootFolder(renamed.getRootFolder() != null ? renamed.getRootFolder().toString() : null)
                            .dbFile(renamed.getDbFile())
                            .type(renamed.getType())
                            .booksCount(-1L)
                            .build();
                    int index = collectionList.indexOf(collection);
                    if (index >= 0) {
                        collectionList.set(index, updated);
                    }
                    appState.getStatusBar().setStatusText("Колекцію перейменовано на '" + newName + "'");
                } catch (Exception e) {
                    dialogService.showError("Помилка", e.getMessage());
                }
            }
        });
    }

    public void showDeleteCollectionDialog(CollectionDto collection, ObservableList<CollectionDto> collectionList) {
        if (collection == null) {
            dialogService.showWarning("Помилка", "Не вибрано колекцію");
            return;
        }

        Collection current = collectionManagementService.getCurrentCollection();
        if (current != null && current.getId().equals(collection.getId())) {
            dialogService.showWarning("Увага", "Неможливо видалити поточну колекцію",
                    "Спочатку виберіть іншу колекцію, а потім спробуйте видалити цю.");
            return;
        }

        if (dialogService.showConfirmation("Підтвердження",
                "Видалити колекцію '" + collection.getName() + "'?",
                "Книги не будуть видалені, тільки колекція.")) {
            try {
                deleteCollectionUseCase.execute(collection.getId());
                collectionList.remove(collection);
                appState.getStatusBar().setStatusText("Колекцію видалено");
            } catch (Exception e) {
                dialogService.showError("Помилка", e.getMessage());
            }
        }
    }

    public void loadCollections(ObservableList<CollectionDto> collectionList) {
        try {
            var collections = loadCollectionsUseCase.execute();
            collectionList.setAll(collections);
            log.info("Завантажено {} колекцій", collections.size());
        } catch (Exception e) {
            log.error("Помилка завантаження колекцій", e);
            dialogService.showError("Помилка", "Не вдалося завантажити колекції: " + e.getMessage());
        }
    }

    // ===== Делеговані методи =====

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

    public void vacuumCurrentCollection() {
        Collection current = getCurrentCollection();
        if (current != null) {
            collectionManagementService.vacuum(current);
        }
    }
}