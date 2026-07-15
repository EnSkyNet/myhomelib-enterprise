-- Додаємо відсутні колонки до таблиці library_statistics
ALTER TABLE library_statistics ADD COLUMN languages_count INTEGER DEFAULT 0;
ALTER TABLE library_statistics ADD COLUMN publishers_count INTEGER DEFAULT 0;
ALTER TABLE library_statistics ADD COLUMN total_size_bytes INTEGER DEFAULT 0;
ALTER TABLE library_statistics ADD COLUMN duplicates_count INTEGER DEFAULT 0;
ALTER TABLE library_statistics ADD COLUMN missing_covers_count INTEGER DEFAULT 0;