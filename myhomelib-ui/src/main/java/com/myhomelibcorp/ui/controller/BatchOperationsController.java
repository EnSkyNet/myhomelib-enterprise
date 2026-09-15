package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.GroupDto;
import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.usecase.book.MarkAsReadBatchUseCase;
import com.myhomelibcorp.ui.service.BookDownloadCoordinator;
import com.myhomelibcorp.ui.util.UiExceptionMessages;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.application.usecase.book.UpdateProgressBatchUseCase;
import com.myhomelibcorp.application.usecase.book.UpdateRateBatchUseCase;
import com.myhomelibcorp.application.usecase.group.AddToGroupBatchUseCase;
import com.myhomelibcorp.application.usecase.group.LoadGroupsUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.service.BookLoaderService;
import com.myhomelibcorp.ui.service.BookSelectionService;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class BatchOperationsController {

    private final DialogService dialogService;
    private final BookLoaderService bookLoaderService;
    private final BookSelectionService bookSelectionService;
    private final BookDownloadCoordinator bookDownloadCoordinator;
    private final UiBackgroundExecutor executor;
    private final BookSaver bookSaver;

    private final UpdateRateBatchUseCase updateRateBatchUseCase;
    private final UpdateProgressBatchUseCase updateProgressBatchUseCase;
    private final MarkAsReadBatchUseCase markAsReadBatchUseCase;
    private final AddToGroupBatchUseCase addToGroupBatchUseCase;
    private final LoadGroupsUseCase loadGroupsUseCase;


    /** Downloads only books explicitly checked through the shared batch selection source. */
    public boolean handleBatchDownload(Runnable onComplete) {
        return handleBatchDownload(null, onComplete);
    }

    public boolean handleBatchDownload(javafx.stage.Window owner, Runnable onComplete) {
        List<BookId> selected = getSelectedBookIds();
        if (selected.isEmpty()) {
            showNoBatchSelection();
            return true;
        }

        bookDownloadCoordinator.downloadBatch(selected, owner).whenComplete((result, error) -> UiExecutor.runOnUiThread(() -> {
            if (error != null) {
                dialogService.showWarning("Завантаження", "Не вдалося завершити пакетне завантаження: " + error.getMessage());
            } else {
                // Download is a terminal batch action: after processing, checkboxes must not remain stale.
                // This applies to newly downloaded and already-local books alike.
                handleClearSelection();
                if (result != null && result.failed() > 0) {
                    dialogService.showWarning("Завантаження завершено",
                            "Завантажено: " + result.downloaded() + ", уже локальні: " + result.alreadyLocal()
                                    + ", помилок: " + result.failed() + ".");
                }
            }
            if (onComplete != null) onComplete.run();
        }));
        return true;
    }

    public boolean handleBatchRemoveLocal(Runnable onComplete) {
        List<BookId> selected = getSelectedBookIds();
        if (selected.isEmpty()) {
            showNoBatchSelection();
            return true;
        }
        bookDownloadCoordinator.removeLocalCopies(selected).whenComplete((count, error) -> UiExecutor.runOnUiThread(() -> {
            if (error == null) {
                handleClearSelection();
                if (onComplete != null) onComplete.run();
            }
        }));
        return true;
    }

    public boolean handleBatchDelete(Runnable onComplete) {
        List<BookId> selected = getSelectedBookIds();
        if (selected.isEmpty()) {
            showNoBatchSelection();
            return true;
        }
        if (!dialogService.showConfirmation("Видалення записів із каталогу",
                "Видалити вибрані записи: " + selected.size() + "?",
                "Файли на диску НЕ видаляються. Цю дію буде застосовано лише до каталогу.")) return true;
        executor.submit(() -> {
            selected.forEach(bookSaver::deleteBook);
            return selected.size();
        }).whenComplete((count, error) -> UiExecutor.runOnUiThread(() -> {
            if (error == null) {
                dialogService.showInfo("Готово", "Видалено записів із каталогу: " + count);
                handleClearSelection();
                if (onComplete != null) onComplete.run();
            } else {
                dialogService.showError("Помилка", "Не вдалося видалити вибрані записи: " + error.getMessage());
            }
        }));
        return true;
    }

    public void handleBatchRate(Runnable onComplete) {
        List<BookId> selected = getSelectedBookIds();
        if (selected.isEmpty()) {
            dialogService.showWarning("Немає вибраних книг", "Будь ласка, виберіть книги за допомогою чекбоксів.");
            return;
        }
        List<Integer> rates = List.of(1, 2, 3, 4, 5);
        Optional<Integer> result = dialogService.showChoiceDialog(
                rates, 5, "Оцінка", "Виберіть рейтинг для " + selected.size() + " книг", "Рейтинг:");
        result.ifPresent(rate -> executor.submit(() -> {
            updateRateBatchUseCase.execute(selected, rate);
            return null;
        }).whenComplete((ignored, error) -> UiExecutor.runOnUiThread(() -> {
            if (error != null) {
                log.error("Помилка масового оновлення рейтингу", error);
                dialogService.showError("Помилка", "Не вдалося оновити рейтинг: " + UiExceptionMessages.root(error));
                return;
            }
            dialogService.showInfo("Успішно", "Рейтинг оновлено для " + selected.size() + " книг.");
            clearSelection();
            if (onComplete != null) onComplete.run();
        })));
    }

    public void handleBatchProgress(Runnable onComplete) {
        List<BookId> selected = getSelectedBookIds();
        if (selected.isEmpty()) {
            dialogService.showWarning("Немає вибраних книг", "Будь ласка, виберіть книги за допомогою чекбоксів.");
            return;
        }
        Optional<String> entered = dialogService.showTextInput(
                "Прогрес читання",
                "Встановити прогрес для " + selected.size() + " книг",
                "Прогрес, % (0–100):",
                "50");
        if (entered.isEmpty()) return;

        final int progress;
        try {
            progress = Integer.parseInt(entered.get().trim());
        } catch (NumberFormatException e) {
            dialogService.showWarning("Некоректний прогрес", "Введіть ціле число від 0 до 100.");
            return;
        }
        if (progress < 0 || progress > 100) {
            dialogService.showWarning("Некоректний прогрес", "Прогрес має бути в межах від 0 до 100%.");
            return;
        }

        executor.submit(() -> {
            updateProgressBatchUseCase.execute(selected, progress);
            return null;
        }).whenComplete((ignored, error) -> UiExecutor.runOnUiThread(() -> {
            if (error != null) {
                log.error("Помилка масового оновлення прогресу", error);
                dialogService.showError("Помилка", "Не вдалося оновити прогрес: " + UiExceptionMessages.root(error));
                return;
            }
            dialogService.showInfo("Успішно", "Прогрес " + progress + "% встановлено для " + selected.size() + " книг.");
            clearSelection();
            if (onComplete != null) onComplete.run();
        }));
    }

    public void handleBatchMarkRead(Runnable onComplete) {
        List<BookId> selected = getSelectedBookIds();
        if (selected.isEmpty()) {
            dialogService.showWarning("Немає вибраних книг", "Будь ласка, виберіть книги за допомогою чекбоксів.");
            return;
        }
        if (dialogService.showConfirmation("Підтвердження", "Помітити вибрані книги як прочитані?",
                "Прогрес буде встановлено на 100% для " + selected.size() + " книг.")) {
            executor.submit(() -> {
                markAsReadBatchUseCase.execute(selected);
                return null;
            }).whenComplete((ignored, error) -> UiExecutor.runOnUiThread(() -> {
                if (error != null) {
                    log.error("Помилка масового позначення прочитаним", error);
                    dialogService.showError("Помилка", "Не вдалося позначити: " + UiExceptionMessages.root(error));
                    return;
                }
                dialogService.showInfo("Успішно", selected.size() + " книг позначено як прочитані.");
                clearSelection();
                if (onComplete != null) onComplete.run();
            }));
        }
    }

    public void handleBatchAddToGroup(Runnable onComplete) {
        List<BookId> selected = getSelectedBookIds();
        if (selected.isEmpty()) {
            dialogService.showWarning("Немає вибраних книг", "Будь ласка, виберіть книги за допомогою чекбоксів.");
            return;
        }
        executor.submit(loadGroupsUseCase::execute)
                .whenComplete((groups, loadError) -> UiExecutor.runOnUiThread(() -> {
                    if (loadError != null) {
                        log.error("Помилка завантаження груп", loadError);
                        dialogService.showError("Помилка", "Не вдалося завантажити групи: " + UiExceptionMessages.root(loadError));
                        return;
                    }
                    if (groups == null || groups.isEmpty()) {
                        dialogService.showWarning("Немає груп", "Створіть групу перед додаванням книг.");
                        return;
                    }
                    Optional<GroupDto> group = dialogService.showChoiceDialog(
                            groups, groups.get(0), "Додати до групи",
                            "Виберіть групу для " + selected.size() + " книг", "Група:");
                    group.ifPresent(g -> executor.submit(() -> {
                        addToGroupBatchUseCase.execute(g.getId(), selected);
                        return null;
                    }).whenComplete((ignored, error) -> UiExecutor.runOnUiThread(() -> {
                        if (error != null) {
                            log.error("Помилка масового додавання до групи", error);
                            dialogService.showError("Помилка", "Не вдалося додати книги: " + UiExceptionMessages.root(error));
                            return;
                        }
                        dialogService.showInfo("Успішно", selected.size() + " книг додано до групи «" + g.getName() + "».");
                        clearSelection();
                        if (onComplete != null) onComplete.run();
                    })));
                }));
    }

    public void handleClearSelection() {
        bookSelectionService.clear();
    }

    private void clearSelection() {
        handleClearSelection();
        bookLoaderService.reloadLastQuery();
    }

    public List<BookId> getSelectedBookIds() {
        return bookSelectionService.snapshot();
    }

    private void showNoBatchSelection() {
        dialogService.showWarning("Немає вибраних книг",
                "Відмітьте книги checkbox. Поточний рядок не підміняє пакетний вибір.");
    }
}