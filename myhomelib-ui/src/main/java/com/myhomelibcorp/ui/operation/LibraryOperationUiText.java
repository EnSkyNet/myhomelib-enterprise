package com.myhomelibcorp.ui.operation;

import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.progress.OperationStage;

/** Ukrainian user-facing text for serialized/background library operations. */
public final class LibraryOperationUiText {
    private LibraryOperationUiText() { }

    public static String activeStatus(LibraryOperationType operation) {
        if (operation == null) return "";
        return switch (operation) {
            case INDEX -> "Оновлення пошукового індексу у фоні…";
            case UPDATE -> "Оновлення колекції у фоні…";
            case IMPORT -> "Імпорт каталогу у фоні…";
            case BACKUP -> "Створення резервної копії…";
            case RESTORE -> "Відновлення колекції…";
            case VACUUM -> "Оптимізація бази даних…";
            case SWITCH -> "Перемикання колекції…";
            case DELETE -> "Видалення колекції…";
            case CREATE -> "Створення колекції…";
            case SYNC -> "Синхронізація колекції…";
            case INTEGRITY_AUDIT -> "Перевірка цілісності колекції…";
        };
    }

    public static String blockingMessage(String requestedAction, LibraryOperationType activeOperation) {
        String requested = requestedAction == null || requestedAction.isBlank() ? "Операція" : requestedAction.trim();
        String active = activeOperationName(activeOperation);
        return requested + " недоступне: зараз виконується " + active + ". Дочекайтеся завершення фонової операції.";
    }

    public static String entryStatus(OperationCenterEntry entry) {
        if (entry == null) return "Виконується фонова операція…";
        String base = switch (entry.stage()) {
            case CHECKING_SERVER -> "Перевірка сервера оновлень…";
            case DOWNLOADING -> "Завантаження даних…";
            case VALIDATING -> "Перевірка завантажених даних…";
            case CREATING_CHECKPOINT -> "Створення точки відновлення…";
            case READING_CATALOG -> "Читання каталогу…";
            case IMPORTING -> "Імпорт каталогу…";
            case UPDATING_AUTHORS -> "Оновлення авторів…";
            case APPLYING_DELETIONS -> "Застосування змін каталогу…";
            case UPDATING_SEARCH_INDEX -> "Оновлення пошукового індексу…";
            case REFRESHING_STATISTICS -> "Оновлення статистики бібліотеки…";
            case ROLLING_BACK -> "Відновлення попереднього стану…";
            case INTEGRITY_CHECKS -> "Перевірка цілісності колекції…";
            case SCANNING_DUPLICATES -> "Пошук дублікатів…";
            case SYNCHRONIZING_FILES -> "Синхронізація файлів…";
            case OPTIMIZING_DATABASE -> "Оптимізація бази даних…";
            case BACKING_UP -> "Створення резервної копії…";
            case RESTORING -> "Відновлення з резервної копії…";
            case CREATING_COLLECTION -> "Створення колекції…";
            case DELETING_COLLECTION -> "Видалення колекції…";
            case FINALIZING -> "Завершення фонової операції…";
            case BOOK_DOWNLOAD -> "Завантаження книги…";
            case COMPLETED, CANCELLED, FAILED -> entry.title();
        };
        double fraction = entry.fraction();
        if (fraction >= 0.0 && fraction <= 1.0) {
            int percent = (int) Math.round(fraction * 100.0);
            return base + " " + percent + "%";
        }
        if (entry.currentItem() != null && !entry.currentItem().isBlank()
                && entry.currentItem().length() <= 80
                && entry.stage() != OperationStage.UPDATING_SEARCH_INDEX) {
            return base + " " + entry.currentItem();
        }
        return base;
    }

    public static boolean matches(LibraryOperationType operation, OperationCenterEntry entry) {
        if (operation == null || entry == null || !entry.active()) return false;
        return switch (operation) {
            case INDEX -> entry.kind() == OperationKind.INDEX_REBUILD || entry.stage() == OperationStage.UPDATING_SEARCH_INDEX;
            case UPDATE -> entry.kind() == OperationKind.CATALOG_UPDATE;
            case IMPORT -> entry.kind() == OperationKind.CATALOG_IMPORT || entry.kind() == OperationKind.CATALOG_UPDATE;
            case BACKUP -> entry.kind() == OperationKind.BACKUP;
            case RESTORE -> entry.kind() == OperationKind.RESTORE;
            case CREATE -> entry.kind() == OperationKind.COLLECTION_CREATE;
            case DELETE -> entry.kind() == OperationKind.COLLECTION_DELETE;
            case INTEGRITY_AUDIT -> entry.kind() == OperationKind.INTEGRITY_CHECK;
            case VACUUM, SYNC, SWITCH -> entry.kind() == OperationKind.MAINTENANCE || entry.kind() == OperationKind.GENERIC;
        };
    }

    private static String activeOperationName(LibraryOperationType operation) {
        if (operation == null) return "інша операція з бібліотекою";
        return switch (operation) {
            case INDEX -> "оновлення пошукового індексу";
            case UPDATE -> "оновлення колекції";
            case IMPORT -> "імпорт каталогу";
            case BACKUP -> "резервне копіювання";
            case RESTORE -> "відновлення колекції";
            case VACUUM -> "оптимізація бази даних";
            case SWITCH -> "перемикання колекції";
            case DELETE -> "видалення колекції";
            case CREATE -> "створення колекції";
            case SYNC -> "синхронізація колекції";
            case INTEGRITY_AUDIT -> "перевірка цілісності";
        };
    }
}
