-- v7.1: group workspaces and group-filtered catalogue queries must start from
-- group_id instead of scanning the full books table. The legacy PK is
-- (book_id, group_id), which is optimal for membership checks but not for
-- enumerating one group's books.
CREATE INDEX IF NOT EXISTS idx_book_groups_group_book
    ON book_groups(group_id, book_id);
