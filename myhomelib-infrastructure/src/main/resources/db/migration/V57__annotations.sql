-- MHL-201/MHL-202: renderer-independent, restart-safe highlights and notes.
CREATE TABLE IF NOT EXISTS annotations (
    id TEXT PRIMARY KEY,
    book_id TEXT NOT NULL,
    artifact_id TEXT,
    annotation_type TEXT NOT NULL CHECK(annotation_type IN ('HIGHLIGHT','NOTE')),
    color TEXT NOT NULL DEFAULT '#FFF59D',
    note TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS annotation_anchors (
    annotation_id TEXT PRIMARY KEY,
    chapter_id TEXT,
    chapter_title TEXT,
    paragraph_id TEXT,
    start_offset INTEGER NOT NULL CHECK(start_offset >= 0),
    end_offset INTEGER NOT NULL CHECK(end_offset >= start_offset),
    position REAL NOT NULL DEFAULT 0 CHECK(position >= 0 AND position <= 1),
    quote_text TEXT NOT NULL DEFAULT '',
    prefix_text TEXT NOT NULL DEFAULT '',
    suffix_text TEXT NOT NULL DEFAULT '',
    FOREIGN KEY(annotation_id) REFERENCES annotations(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS annotation_tags (
    annotation_id TEXT NOT NULL,
    tag TEXT NOT NULL COLLATE NOCASE,
    PRIMARY KEY(annotation_id, tag),
    FOREIGN KEY(annotation_id) REFERENCES annotations(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_annotations_book_updated
    ON annotations(book_id, updated_at DESC, id);
CREATE INDEX IF NOT EXISTS idx_annotations_book_type
    ON annotations(book_id, annotation_type, updated_at DESC, id);
CREATE INDEX IF NOT EXISTS idx_annotations_artifact
    ON annotations(artifact_id, book_id) WHERE artifact_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_annotation_anchors_chapter
    ON annotation_anchors(chapter_id, annotation_id) WHERE chapter_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_annotation_tags_tag
    ON annotation_tags(tag, annotation_id);
