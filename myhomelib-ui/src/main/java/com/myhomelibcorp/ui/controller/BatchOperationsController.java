package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.GroupDto;
import com.myhomelibcorp.application.usecase.book.MarkAsReadBatchUseCase;
import com.myhomelibcorp.application.usecase.book.UpdateProgressBatchUseCase;
import com.myhomelibcorp.application.usecase.book.UpdateRateBatchUseCase;
import com.myhomelibcorp.application.usecase.group.AddToGroupBatchUseCase;
import com.myhomelibcorp.application.usecase.group.LoadGroupsUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.service.BookLoaderService;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class BatchOperationsController {

    private final ApplicationState appState;
    private final DialogService dialogService;
    private final BookLoaderService bookLoaderService;

    private final UpdateRateBatchUseCase updateRateBatchUseCase;
    private final UpdateProgressBatchUseCase updateProgressBatchUseCase;
    private final MarkAsReadBatchUseCase markAsReadBatchUseCase;
    private final AddToGroupBatchUseCase addToGroupBatchUseCase;
    private final LoadGroupsUseCase loadGroupsUseCase;

    public void handleBatchRate(Runnable onComplete) {
        List<BookId> selected = getSelectedBookIds();
        if (selected.isEmpty()) {
            dialogService.showWarning("Немає вибраних книг", "Будь ласка, виберіть книги за допомогою чекбоксів.");
            return;
        }
        List<Integer> rates = List.of(1, 2, 3, 4, 5);
        Optional<Integer> result = dialogService.showChoiceDialog(
                rates, 5, "Оцінка", "Виберіть рейтинг для " + selected.size() + " книг", "Рейтинг:");
        result.ifPresent(rate -> {
            try {
                updateRateBatchUseCase.execute(selected, rate);
                dialogService.showInfo("Успішно", "Рейтинг оновлено для " + selected.size() + " книг.");
                clearSelection();
                if (onComplete != null) {
                    onComplete.run();
                }
            } catch (Exception e) {
                log.error("Помилка масового оновлення рейтингу", e);
                dialogService.showError("Помилка", "Не вдалося оновити рейтинг: " + e.getMessage());
            }
        });
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

        try {
            updateProgressBatchUseCase.execute(selected, progress);
            dialogService.showInfo("Успішно", "Прогрес " + progress + "% встановлено для " + selected.size() + " книг.");
            clearSelection();
            if (onComplete != null) onComplete.run();
        } catch (Exception e) {
            log.error("Помилка масового оновлення прогресу", e);
            dialogService.showError("Помилка", "Не вдалося оновити прогрес: " + e.getMessage());
        }
    }

    public void handleBatchMarkRead(Runnable onComplete) {
        List<BookId> selected = getSelectedBookIds();
        if (selected.isEmpty()) {
            dialogService.showWarning("Немає вибраних книг", "Будь ласка, виберіть книги за допомогою чекбоксів.");
            return;
        }
        if (dialogService.showConfirmation("Підтвердження", "Помітити вибрані книги як прочитані?",
                "Прогрес буде встановлено на 100% для " + selected.size() + " книг.")) {
            try {
                markAsReadBatchUseCase.execute(selected);
                dialogService.showInfo("Успішно", selected.size() + " книг позначено як прочитані.");
                clearSelection();
                if (onComplete != null) {
                    onComplete.run();
                }
            } catch (Exception e) {
                log.error("Помилка масового позначення прочитаним", e);
                dialogService.showError("Помилка", "Не вдалося позначити: " + e.getMessage());
            }
        }
    }

    public void handleBatchAddToGroup(Runnable onComplete) {
        List<BookId> selected = getSelectedBookIds();
        if (selected.isEmpty()) {
            dialogService.showWarning("Немає вибраних книг", "Будь ласка, виберіть книги за допомогою чекбоксів.");
            return;
        }
        List<GroupDto> groups = loadGroupsUseCase.execute();
        if (groups.isEmpty()) {
            dialogService.showWarning("Немає груп", "Створіть групу перед додаванням книг.");
            return;
        }
        Optional<GroupDto> group = dialogService.showChoiceDialog(
                groups, groups.get(0), "Додати до групи",
                "Виберіть групу для " + selected.size() + " книг", "Група:");
        group.ifPresent(g -> {
            try {
                addToGroupBatchUseCase.execute(g.getId(), selected);
                dialogService.showInfo("Успішно", selected.size() + " книг додано до групи \"" + g.getName() + "\".");
                clearSelection();
                if (onComplete != null) {
                    onComplete.run();
                }
            } catch (Exception e) {
                log.error("Помилка масового додавання до групи", e);
                dialogService.showError("Помилка", "Не вдалося додати книги: " + e.getMessage());
            }
        });
    }

    public void handleClearSelection() {
        appState.getBookTable().getBooks().forEach(book -> book.setSelected(false));
    }

    private void clearSelection() {
        handleClearSelection();
        bookLoaderService.reloadLastQuery();
    }

    private List<BookId> getSelectedBookIds() {
        return appState.getBookTable().getBooks().stream()
                .filter(BookViewModel::isSelected)
                .map(b -> BookId.fromString(b.getId()))
                .collect(Collectors.toList());
    }
}