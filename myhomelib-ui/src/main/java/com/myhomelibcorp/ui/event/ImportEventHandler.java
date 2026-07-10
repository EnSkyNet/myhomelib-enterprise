package com.myhomelibcorp.ui.event;

import com.myhomelibcorp.application.event.ImportFinishedEvent;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Обробник подій імпорту, який оновлює UI-стан після завершення імпорту.
 * Розташований у UI-шарі, оскільки працює з ApplicationState.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImportEventHandler {

    private final ApplicationState appState;
    private final StatisticsService statisticsService;

    /**
     * Ініціалізація підписки на події.
     * Якщо використовується Spring Event Bus – метод з @EventListener буде викликано автоматично.
     * Якщо використовується кастомний EventBus – реєструємо підписку тут.
     */
    @PostConstruct
    public void init() {
        // Якщо використовуєте кастомний EventBus, розкоментуйте:
        // eventBus.subscribe(ImportFinishedEvent.class, this::onImportFinished);
        log.info("ImportEventHandler ініціалізовано");
    }

    /**
     * Обробка події завершення імпорту.
     * Використовує анотацію @EventListener, якщо проект використовує Spring Events.
     * Якщо використовується кастомний EventBus, викличте цей метод вручну з підписки.
     */
    // @EventListener – розкоментуйте, якщо використовуєте Spring Events
    public void onImportFinished(ImportFinishedEvent event) {
        log.info("Отримано подію ImportFinishedEvent: +{} книг", event.getImported());

        UiExecutor.runOnUiThread(() -> {
            // Оновлюємо статус
            String status;
            if (event.isSuccess()) {
                status = String.format("Імпорт завершено: +%d книг, помилок: %d",
                        event.getImported(), event.getErrors());
            } else {
                status = String.format("Імпорт завершено з помилками: +%d книг, помилок: %d",
                        event.getImported(), event.getErrors());
            }
            appState.getStatusBar().setStatusText(status);

            // Оновлюємо статистику
            var stats = statisticsService.getStatistics();
            appState.getStatusBar().setStatistics(stats);
            appState.getDashboard().setStatistics(stats);

            // Приховуємо прогрес-бар
            appState.getStatusBar().setProgressVisible(false);

            // Якщо потрібно оновити список книг або дашборд – робимо це тут
            // Наприклад, через BookLoaderService.loadAllBooks() або повторне завантаження Dashboard
            // bookLoaderService.loadAllBooks(); // якщо потрібно
        });
    }

    /**
     * Альтернативний метод для ручного виклику (наприклад, з кастомного EventBus).
     */
    public void handleImportFinished(ImportFinishedEvent event) {
        onImportFinished(event);
    }
}