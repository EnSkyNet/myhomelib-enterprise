package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.usecase.collection.CreateCollectionUseCase;
import com.myhomelibcorp.application.usecase.collection.DeleteCollectionUseCase;
import com.myhomelibcorp.application.usecase.collection.LoadCollectionsUseCase;
import com.myhomelibcorp.application.usecase.collection.RenameCollectionUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.initializer.DatabaseInitializer;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FileChooserService;
import javafx.collections.ObservableList;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Optional;

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
    private final StatusBarPresenter statusBarPresenter;
    private final CollectionManager collectionManager;
    private final DatabaseInitializer databaseInitializer;

    public void showCreateCollectionDialog(ObservableList<Collection> collectionList, Stage owner) {
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
                    java.util.UUID.randomUUID() + ".db";
            dbFilePath = defaultPath;
        }

        try {
            Collection collection = new Collection(
                    null,
                    name,
                    null,
                    dbFilePath,
                    0,
                    null,
                    null,
                    null,
                    null
            );
            Collection saved = createCollectionUseCase.execute(
                    collection.getName(),
                    collection.getRootFolder() != null ? collection.getRootFolder().toString() : null
            );
            collectionList.add(saved);
            statusBarPresenter.setStatus("Колекцію '" + name + "' створено");

            // Перемикаємось на нову колекцію
            collectionManager.switchToCollection(saved);

            // Ініціалізуємо БД нової колекції (Flyway міграції)
            try {
                databaseInitializer.initializeCurrentCollection();
                statusBarPresenter.setStatus("Базу даних колекції ініціалізовано");
            } catch (Exception e) {
                log.error("Помилка ініціалізації БД колекції", e);
                dialogService.showError("Помилка", "Не вдалося ініціалізувати БД колекції:\n" + e.getMessage());
                // Можна видалити щойно створену колекцію, але залишаємо для діагностики
            }

            statusBarPresenter.setStatus("Переключено на колекцію: " + saved.getName());

        } catch (Exception e) {
            dialogService.showError("Помилка", e.getMessage());
            log.error("Помилка створення колекції", e);
        }
    }

    public void showRenameCollectionDialog(Collection collection, ObservableList<Collection> collectionList) {
        if (collection == null) {
            dialogService.showError("Помилка", "Не вибрано колекцію");
            return;
        }
        Optional<String> result = dialogService.showTextInput("Перейменувати колекцію",
                "Введіть нову назву колекції", "Назва:", collection.getName());
        result.ifPresent(newName -> {
            if (!newName.isBlank() && !newName.equals(collection.getName())) {
                try {
                    Collection renamed = renameCollectionUseCase.execute(collection.getId(), newName);
                    int index = collectionList.indexOf(collection);
                    if (index >= 0) {
                        collectionList.set(index, renamed);
                    }
                    statusBarPresenter.setStatus("Колекцію перейменовано на '" + newName + "'");
                } catch (Exception e) {
                    dialogService.showError("Помилка", e.getMessage());
                }
            }
        });
    }

    public void showDeleteCollectionDialog(Collection collection, ObservableList<Collection> collectionList) {
        if (collection == null) {
            dialogService.showError("Помилка", "Не вибрано колекцію");
            return;
        }

        Collection current = collectionManager.getCurrentCollection();
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
                statusBarPresenter.setStatus("Колекцію видалено");
            } catch (Exception e) {
                dialogService.showError("Помилка", e.getMessage());
            }
        }
    }

    public void loadCollections(ObservableList<Collection> collectionList) {
        try {
            var collections = loadCollectionsUseCase.execute();
            collectionList.setAll(collections);
        } catch (Exception e) {
            log.error("Помилка завантаження колекцій", e);
            dialogService.showError("Помилка", "Не вдалося завантажити колекції: " + e.getMessage());
        }
    }
}