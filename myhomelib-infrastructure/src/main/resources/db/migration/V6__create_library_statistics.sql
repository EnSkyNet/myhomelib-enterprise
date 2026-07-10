-- ============================================================
-- Таблиця для зберігання загальної статистики бібліотеки
-- Оновлюється після імпорту або масових змін.
-- ============================================================

CREATE TABLE IF NOT EXISTS library_statistics (
                                                  id INTEGER PRIMARY KEY CHECK (id = 1),
    books_count INTEGER NOT NULL DEFAULT 0,
    authors_count INTEGER NOT NULL DEFAULT 0,
    genres_count INTEGER NOT NULL DEFAULT 0,
    series_count INTEGER NOT NULL DEFAULT 0,
    groups_count INTEGER NOT NULL DEFAULT 0,
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- Вставляємо початковий запис
INSERT OR IGNORE INTO library_statistics (id, books_count, authors_count, genres_count, series_count, groups_count)
VALUES (1, 0, 0, 0, 0, 0);

-- Тригер для автоматичного оновлення статистики при зміні кількості книг (можна додати за потреби)
-- Але краще оновлювати вручну через репозиторій.