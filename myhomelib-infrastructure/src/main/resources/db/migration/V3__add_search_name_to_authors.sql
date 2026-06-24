-- Додаємо поле для нормалізованого пошукового імені автора
ALTER TABLE authors ADD COLUMN search_name TEXT;

-- Створюємо індекс для швидкого пошуку (без COLLATE NOCASE, оскільки ми зберігаємо в нижньому регістрі)
CREATE INDEX IF NOT EXISTS idx_authors_search_name ON authors(search_name);