-- MHL-306 hardening: avoid O(n) scans of the UNINDEXED annotation_id column on every FTS refresh.
-- Keep a stable integer key outside the FTS table and address FTS rows by rowid instead.

DROP TRIGGER IF EXISTS trg_annotation_search_annotations_ai;
DROP TRIGGER IF EXISTS trg_annotation_search_annotations_au;
DROP TRIGGER IF EXISTS trg_annotation_search_annotations_ad;
DROP TRIGGER IF EXISTS trg_annotation_search_anchors_ai;
DROP TRIGGER IF EXISTS trg_annotation_search_anchors_au;
DROP TRIGGER IF EXISTS trg_annotation_search_anchors_ad;
DROP TRIGGER IF EXISTS trg_annotation_search_tags_ai;
DROP TRIGGER IF EXISTS trg_annotation_search_tags_au;
DROP TRIGGER IF EXISTS trg_annotation_search_tags_ad;
DROP TRIGGER IF EXISTS trg_annotation_search_books_au;

CREATE TABLE IF NOT EXISTS annotation_search_ids (
    fts_rowid INTEGER PRIMARY KEY AUTOINCREMENT,
    annotation_id TEXT NOT NULL UNIQUE,
    FOREIGN KEY(annotation_id) REFERENCES annotations(id) ON DELETE CASCADE
);

INSERT OR IGNORE INTO annotation_search_ids(annotation_id)
SELECT id FROM annotations ORDER BY id;

DELETE FROM annotation_search_ids
 WHERE annotation_id NOT IN (SELECT id FROM annotations);

-- Rebuild once so every existing row gets the stable integer rowid.
DROP TABLE IF EXISTS annotation_search_fts;
CREATE VIRTUAL TABLE annotation_search_fts USING fts5(
    annotation_id UNINDEXED,
    book_title,
    note,
    quote_text,
    chapter_title,
    tags,
    tokenize = 'unicode61 remove_diacritics 2'
);

INSERT INTO annotation_search_fts(rowid,annotation_id,book_title,note,quote_text,chapter_title,tags)
SELECT m.fts_rowid,
       a.id,
       COALESCE(b.title,''),
       COALESCE(a.note,''),
       COALESCE(x.quote_text,''),
       COALESCE(x.chapter_title,''),
       COALESCE((SELECT group_concat(t.tag,' ') FROM annotation_tags t WHERE t.annotation_id=a.id),'')
  FROM annotation_search_ids m
  JOIN annotations a ON a.id=m.annotation_id
  LEFT JOIN annotation_anchors x ON x.annotation_id=a.id
  LEFT JOIN books b ON b.id=a.book_id;

CREATE TRIGGER trg_annotation_search_annotations_ai
AFTER INSERT ON annotations BEGIN
    INSERT OR IGNORE INTO annotation_search_ids(annotation_id) VALUES(NEW.id);
    DELETE FROM annotation_search_fts
     WHERE rowid=(SELECT fts_rowid FROM annotation_search_ids WHERE annotation_id=NEW.id);
    INSERT INTO annotation_search_fts(rowid,annotation_id,book_title,note,quote_text,chapter_title,tags)
    SELECT m.fts_rowid,a.id,COALESCE(b.title,''),COALESCE(a.note,''),COALESCE(x.quote_text,''),COALESCE(x.chapter_title,''),
           COALESCE((SELECT group_concat(t.tag,' ') FROM annotation_tags t WHERE t.annotation_id=a.id),'')
      FROM annotation_search_ids m
      JOIN annotations a ON a.id=m.annotation_id
      LEFT JOIN annotation_anchors x ON x.annotation_id=a.id
      LEFT JOIN books b ON b.id=a.book_id
     WHERE a.id=NEW.id;
END;

CREATE TRIGGER trg_annotation_search_annotations_au
AFTER UPDATE OF book_id,note,annotation_type ON annotations BEGIN
    DELETE FROM annotation_search_fts
     WHERE rowid=(SELECT fts_rowid FROM annotation_search_ids WHERE annotation_id=NEW.id);
    INSERT INTO annotation_search_fts(rowid,annotation_id,book_title,note,quote_text,chapter_title,tags)
    SELECT m.fts_rowid,a.id,COALESCE(b.title,''),COALESCE(a.note,''),COALESCE(x.quote_text,''),COALESCE(x.chapter_title,''),
           COALESCE((SELECT group_concat(t.tag,' ') FROM annotation_tags t WHERE t.annotation_id=a.id),'')
      FROM annotation_search_ids m
      JOIN annotations a ON a.id=m.annotation_id
      LEFT JOIN annotation_anchors x ON x.annotation_id=a.id
      LEFT JOIN books b ON b.id=a.book_id
     WHERE a.id=NEW.id;
END;

CREATE TRIGGER trg_annotation_search_annotations_bd
BEFORE DELETE ON annotations BEGIN
    DELETE FROM annotation_search_fts
     WHERE rowid=(SELECT fts_rowid FROM annotation_search_ids WHERE annotation_id=OLD.id);
    DELETE FROM annotation_search_ids WHERE annotation_id=OLD.id;
END;

CREATE TRIGGER trg_annotation_search_anchors_ai
AFTER INSERT ON annotation_anchors BEGIN
    DELETE FROM annotation_search_fts
     WHERE rowid=(SELECT fts_rowid FROM annotation_search_ids WHERE annotation_id=NEW.annotation_id);
    INSERT INTO annotation_search_fts(rowid,annotation_id,book_title,note,quote_text,chapter_title,tags)
    SELECT m.fts_rowid,a.id,COALESCE(b.title,''),COALESCE(a.note,''),COALESCE(x.quote_text,''),COALESCE(x.chapter_title,''),
           COALESCE((SELECT group_concat(t.tag,' ') FROM annotation_tags t WHERE t.annotation_id=a.id),'')
      FROM annotation_search_ids m
      JOIN annotations a ON a.id=m.annotation_id
      LEFT JOIN annotation_anchors x ON x.annotation_id=a.id
      LEFT JOIN books b ON b.id=a.book_id
     WHERE a.id=NEW.annotation_id;
END;

CREATE TRIGGER trg_annotation_search_anchors_au
AFTER UPDATE OF chapter_title,quote_text ON annotation_anchors BEGIN
    DELETE FROM annotation_search_fts
     WHERE rowid=(SELECT fts_rowid FROM annotation_search_ids WHERE annotation_id=NEW.annotation_id);
    INSERT INTO annotation_search_fts(rowid,annotation_id,book_title,note,quote_text,chapter_title,tags)
    SELECT m.fts_rowid,a.id,COALESCE(b.title,''),COALESCE(a.note,''),COALESCE(x.quote_text,''),COALESCE(x.chapter_title,''),
           COALESCE((SELECT group_concat(t.tag,' ') FROM annotation_tags t WHERE t.annotation_id=a.id),'')
      FROM annotation_search_ids m
      JOIN annotations a ON a.id=m.annotation_id
      LEFT JOIN annotation_anchors x ON x.annotation_id=a.id
      LEFT JOIN books b ON b.id=a.book_id
     WHERE a.id=NEW.annotation_id;
END;

CREATE TRIGGER trg_annotation_search_anchors_ad
AFTER DELETE ON annotation_anchors BEGIN
    DELETE FROM annotation_search_fts
     WHERE rowid=(SELECT fts_rowid FROM annotation_search_ids WHERE annotation_id=OLD.annotation_id);
    INSERT INTO annotation_search_fts(rowid,annotation_id,book_title,note,quote_text,chapter_title,tags)
    SELECT m.fts_rowid,a.id,COALESCE(b.title,''),COALESCE(a.note,''),'','',
           COALESCE((SELECT group_concat(t.tag,' ') FROM annotation_tags t WHERE t.annotation_id=a.id),'')
      FROM annotation_search_ids m
      JOIN annotations a ON a.id=m.annotation_id
      LEFT JOIN books b ON b.id=a.book_id
     WHERE a.id=OLD.annotation_id;
END;

CREATE TRIGGER trg_annotation_search_tags_ai
AFTER INSERT ON annotation_tags BEGIN
    DELETE FROM annotation_search_fts
     WHERE rowid=(SELECT fts_rowid FROM annotation_search_ids WHERE annotation_id=NEW.annotation_id);
    INSERT INTO annotation_search_fts(rowid,annotation_id,book_title,note,quote_text,chapter_title,tags)
    SELECT m.fts_rowid,a.id,COALESCE(b.title,''),COALESCE(a.note,''),COALESCE(x.quote_text,''),COALESCE(x.chapter_title,''),
           COALESCE((SELECT group_concat(t.tag,' ') FROM annotation_tags t WHERE t.annotation_id=a.id),'')
      FROM annotation_search_ids m
      JOIN annotations a ON a.id=m.annotation_id
      LEFT JOIN annotation_anchors x ON x.annotation_id=a.id
      LEFT JOIN books b ON b.id=a.book_id
     WHERE a.id=NEW.annotation_id;
END;

CREATE TRIGGER trg_annotation_search_tags_au
AFTER UPDATE OF tag,annotation_id ON annotation_tags BEGIN
    DELETE FROM annotation_search_fts
     WHERE rowid IN (SELECT fts_rowid FROM annotation_search_ids WHERE annotation_id IN (OLD.annotation_id,NEW.annotation_id));
    INSERT INTO annotation_search_fts(rowid,annotation_id,book_title,note,quote_text,chapter_title,tags)
    SELECT m.fts_rowid,a.id,COALESCE(b.title,''),COALESCE(a.note,''),COALESCE(x.quote_text,''),COALESCE(x.chapter_title,''),
           COALESCE((SELECT group_concat(t.tag,' ') FROM annotation_tags t WHERE t.annotation_id=a.id),'')
      FROM annotation_search_ids m
      JOIN annotations a ON a.id=m.annotation_id
      LEFT JOIN annotation_anchors x ON x.annotation_id=a.id
      LEFT JOIN books b ON b.id=a.book_id
     WHERE a.id IN (OLD.annotation_id,NEW.annotation_id);
END;

CREATE TRIGGER trg_annotation_search_tags_ad
AFTER DELETE ON annotation_tags BEGIN
    DELETE FROM annotation_search_fts
     WHERE rowid=(SELECT fts_rowid FROM annotation_search_ids WHERE annotation_id=OLD.annotation_id);
    INSERT INTO annotation_search_fts(rowid,annotation_id,book_title,note,quote_text,chapter_title,tags)
    SELECT m.fts_rowid,a.id,COALESCE(b.title,''),COALESCE(a.note,''),COALESCE(x.quote_text,''),COALESCE(x.chapter_title,''),
           COALESCE((SELECT group_concat(t.tag,' ') FROM annotation_tags t WHERE t.annotation_id=a.id),'')
      FROM annotation_search_ids m
      JOIN annotations a ON a.id=m.annotation_id
      LEFT JOIN annotation_anchors x ON x.annotation_id=a.id
      LEFT JOIN books b ON b.id=a.book_id
     WHERE a.id=OLD.annotation_id;
END;

CREATE TRIGGER trg_annotation_search_books_au
AFTER UPDATE OF title ON books BEGIN
    DELETE FROM annotation_search_fts
     WHERE rowid IN (
        SELECT m.fts_rowid
          FROM annotation_search_ids m
          JOIN annotations a ON a.id=m.annotation_id
         WHERE a.book_id=NEW.id
     );
    INSERT INTO annotation_search_fts(rowid,annotation_id,book_title,note,quote_text,chapter_title,tags)
    SELECT m.fts_rowid,a.id,COALESCE(NEW.title,''),COALESCE(a.note,''),COALESCE(x.quote_text,''),COALESCE(x.chapter_title,''),
           COALESCE((SELECT group_concat(t.tag,' ') FROM annotation_tags t WHERE t.annotation_id=a.id),'')
      FROM annotation_search_ids m
      JOIN annotations a ON a.id=m.annotation_id
      LEFT JOIN annotation_anchors x ON x.annotation_id=a.id
     WHERE a.book_id=NEW.id;
END;
