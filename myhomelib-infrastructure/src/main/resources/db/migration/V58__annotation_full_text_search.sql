-- Local-only full-text index for note/highlight search (MHL-306).
-- The index mirrors user annotation text, anchor text, tags and book title inside the active collection DB.
CREATE VIRTUAL TABLE IF NOT EXISTS annotation_search_fts USING fts5(
    annotation_id UNINDEXED,
    book_title,
    note,
    quote_text,
    chapter_title,
    tags,
    tokenize = 'unicode61 remove_diacritics 2'
);

INSERT INTO annotation_search_fts(annotation_id,book_title,note,quote_text,chapter_title,tags)
SELECT a.id,
       COALESCE(b.title,''),
       COALESCE(a.note,''),
       COALESCE(x.quote_text,''),
       COALESCE(x.chapter_title,''),
       COALESCE((SELECT group_concat(t.tag,' ') FROM annotation_tags t WHERE t.annotation_id=a.id),'')
  FROM annotations a
  LEFT JOIN annotation_anchors x ON x.annotation_id=a.id
  LEFT JOIN books b ON b.id=a.book_id
 WHERE NOT EXISTS (SELECT 1 FROM annotation_search_fts f WHERE f.annotation_id=a.id);

CREATE TRIGGER IF NOT EXISTS trg_annotation_search_annotations_ai
AFTER INSERT ON annotations BEGIN
    DELETE FROM annotation_search_fts WHERE annotation_id=NEW.id;
    INSERT INTO annotation_search_fts(annotation_id,book_title,note,quote_text,chapter_title,tags)
    SELECT a.id,COALESCE(b.title,''),COALESCE(a.note,''),COALESCE(x.quote_text,''),COALESCE(x.chapter_title,''),
           COALESCE((SELECT group_concat(t.tag,' ') FROM annotation_tags t WHERE t.annotation_id=a.id),'')
      FROM annotations a LEFT JOIN annotation_anchors x ON x.annotation_id=a.id LEFT JOIN books b ON b.id=a.book_id
     WHERE a.id=NEW.id;
END;

CREATE TRIGGER IF NOT EXISTS trg_annotation_search_annotations_au
AFTER UPDATE OF book_id,note,annotation_type ON annotations BEGIN
    DELETE FROM annotation_search_fts WHERE annotation_id=NEW.id;
    INSERT INTO annotation_search_fts(annotation_id,book_title,note,quote_text,chapter_title,tags)
    SELECT a.id,COALESCE(b.title,''),COALESCE(a.note,''),COALESCE(x.quote_text,''),COALESCE(x.chapter_title,''),
           COALESCE((SELECT group_concat(t.tag,' ') FROM annotation_tags t WHERE t.annotation_id=a.id),'')
      FROM annotations a LEFT JOIN annotation_anchors x ON x.annotation_id=a.id LEFT JOIN books b ON b.id=a.book_id
     WHERE a.id=NEW.id;
END;

CREATE TRIGGER IF NOT EXISTS trg_annotation_search_annotations_ad
AFTER DELETE ON annotations BEGIN
    DELETE FROM annotation_search_fts WHERE annotation_id=OLD.id;
END;

CREATE TRIGGER IF NOT EXISTS trg_annotation_search_anchors_ai
AFTER INSERT ON annotation_anchors BEGIN
    DELETE FROM annotation_search_fts WHERE annotation_id=NEW.annotation_id;
    INSERT INTO annotation_search_fts(annotation_id,book_title,note,quote_text,chapter_title,tags)
    SELECT a.id,COALESCE(b.title,''),COALESCE(a.note,''),COALESCE(x.quote_text,''),COALESCE(x.chapter_title,''),
           COALESCE((SELECT group_concat(t.tag,' ') FROM annotation_tags t WHERE t.annotation_id=a.id),'')
      FROM annotations a LEFT JOIN annotation_anchors x ON x.annotation_id=a.id LEFT JOIN books b ON b.id=a.book_id
     WHERE a.id=NEW.annotation_id;
END;

CREATE TRIGGER IF NOT EXISTS trg_annotation_search_anchors_au
AFTER UPDATE OF chapter_title,quote_text ON annotation_anchors BEGIN
    DELETE FROM annotation_search_fts WHERE annotation_id=NEW.annotation_id;
    INSERT INTO annotation_search_fts(annotation_id,book_title,note,quote_text,chapter_title,tags)
    SELECT a.id,COALESCE(b.title,''),COALESCE(a.note,''),COALESCE(x.quote_text,''),COALESCE(x.chapter_title,''),
           COALESCE((SELECT group_concat(t.tag,' ') FROM annotation_tags t WHERE t.annotation_id=a.id),'')
      FROM annotations a LEFT JOIN annotation_anchors x ON x.annotation_id=a.id LEFT JOIN books b ON b.id=a.book_id
     WHERE a.id=NEW.annotation_id;
END;

CREATE TRIGGER IF NOT EXISTS trg_annotation_search_anchors_ad
AFTER DELETE ON annotation_anchors BEGIN
    DELETE FROM annotation_search_fts WHERE annotation_id=OLD.annotation_id;
    INSERT INTO annotation_search_fts(annotation_id,book_title,note,quote_text,chapter_title,tags)
    SELECT a.id,COALESCE(b.title,''),COALESCE(a.note,''),'','',
           COALESCE((SELECT group_concat(t.tag,' ') FROM annotation_tags t WHERE t.annotation_id=a.id),'')
      FROM annotations a LEFT JOIN books b ON b.id=a.book_id
     WHERE a.id=OLD.annotation_id;
END;

CREATE TRIGGER IF NOT EXISTS trg_annotation_search_tags_ai
AFTER INSERT ON annotation_tags BEGIN
    DELETE FROM annotation_search_fts WHERE annotation_id=NEW.annotation_id;
    INSERT INTO annotation_search_fts(annotation_id,book_title,note,quote_text,chapter_title,tags)
    SELECT a.id,COALESCE(b.title,''),COALESCE(a.note,''),COALESCE(x.quote_text,''),COALESCE(x.chapter_title,''),
           COALESCE((SELECT group_concat(t.tag,' ') FROM annotation_tags t WHERE t.annotation_id=a.id),'')
      FROM annotations a LEFT JOIN annotation_anchors x ON x.annotation_id=a.id LEFT JOIN books b ON b.id=a.book_id
     WHERE a.id=NEW.annotation_id;
END;

CREATE TRIGGER IF NOT EXISTS trg_annotation_search_tags_au
AFTER UPDATE OF tag,annotation_id ON annotation_tags BEGIN
    DELETE FROM annotation_search_fts WHERE annotation_id IN (OLD.annotation_id,NEW.annotation_id);
    INSERT INTO annotation_search_fts(annotation_id,book_title,note,quote_text,chapter_title,tags)
    SELECT a.id,COALESCE(b.title,''),COALESCE(a.note,''),COALESCE(x.quote_text,''),COALESCE(x.chapter_title,''),
           COALESCE((SELECT group_concat(t.tag,' ') FROM annotation_tags t WHERE t.annotation_id=a.id),'')
      FROM annotations a LEFT JOIN annotation_anchors x ON x.annotation_id=a.id LEFT JOIN books b ON b.id=a.book_id
     WHERE a.id IN (OLD.annotation_id,NEW.annotation_id);
END;

CREATE TRIGGER IF NOT EXISTS trg_annotation_search_tags_ad
AFTER DELETE ON annotation_tags BEGIN
    DELETE FROM annotation_search_fts WHERE annotation_id=OLD.annotation_id;
    INSERT INTO annotation_search_fts(annotation_id,book_title,note,quote_text,chapter_title,tags)
    SELECT a.id,COALESCE(b.title,''),COALESCE(a.note,''),COALESCE(x.quote_text,''),COALESCE(x.chapter_title,''),
           COALESCE((SELECT group_concat(t.tag,' ') FROM annotation_tags t WHERE t.annotation_id=a.id),'')
      FROM annotations a LEFT JOIN annotation_anchors x ON x.annotation_id=a.id LEFT JOIN books b ON b.id=a.book_id
     WHERE a.id=OLD.annotation_id;
END;

CREATE TRIGGER IF NOT EXISTS trg_annotation_search_books_au
AFTER UPDATE OF title ON books BEGIN
    DELETE FROM annotation_search_fts WHERE annotation_id IN (SELECT id FROM annotations WHERE book_id=NEW.id);
    INSERT INTO annotation_search_fts(annotation_id,book_title,note,quote_text,chapter_title,tags)
    SELECT a.id,COALESCE(NEW.title,''),COALESCE(a.note,''),COALESCE(x.quote_text,''),COALESCE(x.chapter_title,''),
           COALESCE((SELECT group_concat(t.tag,' ') FROM annotation_tags t WHERE t.annotation_id=a.id),'')
      FROM annotations a LEFT JOIN annotation_anchors x ON x.annotation_id=a.id
     WHERE a.book_id=NEW.id;
END;
