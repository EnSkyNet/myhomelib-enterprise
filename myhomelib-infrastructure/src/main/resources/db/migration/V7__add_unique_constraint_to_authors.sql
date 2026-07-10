-- Видаляємо можливі дублікати (залишаємо перший запис для кожної пари)
DELETE FROM authors
WHERE id NOT IN (
    SELECT MIN(id)
    FROM authors
    GROUP BY first_name, last_name
);

-- Додаємо унікальне обмеження
CREATE UNIQUE INDEX IF NOT EXISTS idx_authors_unique_name ON authors(first_name, last_name);