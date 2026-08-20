-- ============================================================
-- V27: Покращення таблиці reading_progress
-- Додаємо anchor_id та paragraph_index для стабільного відновлення позиції
-- ============================================================

-- Додаємо нові колонки
ALTER TABLE reading_progress ADD COLUMN anchor_id TEXT;
ALTER TABLE reading_progress ADD COLUMN paragraph_index INTEGER DEFAULT 0;

-- Оновлюємо існуючі записи: paragraph_id стає anchor_id
UPDATE reading_progress
SET anchor_id = paragraph_id
WHERE anchor_id IS NULL AND paragraph_id IS NOT NULL;

-- Оновлюємо paragraph_index на основі paragraph_id (якщо це число)
UPDATE reading_progress
SET paragraph_index = CAST(
        REPLACE(paragraph_id, 'p', '') AS INTEGER
                      )
WHERE paragraph_index = 0
  AND paragraph_id IS NOT NULL
  AND paragraph_id LIKE 'p%';

-- Створюємо індекс для швидкого пошуку за anchor_id
CREATE INDEX IF NOT EXISTS idx_reading_progress_anchor_id ON reading_progress(anchor_id);

-- Додаємо NOT NULL для anchor_id після міграції
-- (виконуємо окремо, щоб не зламати існуючі записи)
-- ALTER TABLE reading_progress ADD COLUMN anchor_id NOT NULL; -- закоментовано для безпеки