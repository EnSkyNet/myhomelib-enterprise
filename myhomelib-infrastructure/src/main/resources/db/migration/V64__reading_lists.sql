CREATE TABLE reading_lists (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX ux_reading_lists_normalized_name
    ON reading_lists(lower(trim(name)));

CREATE TABLE reading_list_items (
    list_id TEXT NOT NULL,
    book_id TEXT NOT NULL,
    position INTEGER NOT NULL,
    added_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (list_id, book_id),
    UNIQUE (list_id, position),
    FOREIGN KEY (list_id) REFERENCES reading_lists(id) ON DELETE CASCADE,
    FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE
);

CREATE INDEX idx_reading_list_items_book ON reading_list_items(book_id);

CREATE TRIGGER reading_list_items_compact_after_delete
AFTER DELETE ON reading_list_items
BEGIN
    UPDATE reading_list_items
       SET position = -(position + 1)
     WHERE list_id = OLD.list_id AND position > OLD.position;
    UPDATE reading_list_items
       SET position = -position - 2
     WHERE list_id = OLD.list_id AND position < 0;
END;

-- The catalog uses soft deletion for books. Keep Reading Lists free of logically deleted items;
-- the item-delete trigger above compacts the remaining contiguous positions.
CREATE TRIGGER reading_list_items_remove_soft_deleted_book
AFTER UPDATE OF deleted ON books
WHEN COALESCE(OLD.deleted,0)=0 AND COALESCE(NEW.deleted,0)<>0
BEGIN
    DELETE FROM reading_list_items WHERE book_id=NEW.id;
END;
