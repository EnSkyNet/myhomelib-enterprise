package com.myhomelibcorp.infrastructure.exchange;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.port.out.exchange.UserDataExchangePort;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/**
 * MyHomeLib-compatible exchange of personal state independently from the catalog.
 * Records are keyed by LibID when available and fall back to the internal UUID for
 * locally-created books. This is essential for preserving ratings/progress across
 * a full INPX catalog refresh.
 */
@Component
@Slf4j
public class JsonUserDataExchangeAdapter implements UserDataExchangePort {
    private final CollectionManager collections;
    private final ObjectMapper mapper;

    public JsonUserDataExchangeAdapter(CollectionManager collections, ObjectMapper mapper) {
        this.collections = collections;
        this.mapper = mapper;
    }

    private JdbcTemplate jt() { return collections.getCurrentJdbcTemplate(); }

    @Override
    public void exportTo(Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("format", "MyHomeLib-user-data");
            root.put("version", 2);
            root.put("exportedAt", Instant.now().toString());
            root.put("books", jt().queryForList("""
                    SELECT COALESCE(NULLIF(lib_id,''), id) AS lib_id, id AS internal_id,
                           rate,progress,review
                    FROM books
                    WHERE rate<>0 OR progress<>0 OR COALESCE(review,'')<>''
                    """));
            root.put("groups", jt().queryForList("""
                    SELECT COALESCE(NULLIF(b.lib_id,''), b.id) AS lib_id,
                           b.id AS internal_id, g.name
                    FROM book_groups bg
                    JOIN books b ON b.id=bg.book_id
                    JOIN groups g ON g.id=bg.group_id
                    ORDER BY g.name,b.id
                    """));
            root.put("readingProgress", jt().queryForList("""
                    SELECT COALESCE(NULLIF(b.lib_id,''), b.id) AS lib_id,
                           rp.*
                    FROM reading_progress rp JOIN books b ON b.id=rp.book_id
                    """));
            root.put("bookmarks", jt().queryForList("""
                    SELECT COALESCE(NULLIF(b.lib_id,''), b.id) AS lib_id,
                           bm.*
                    FROM bookmarks bm JOIN books b ON b.id=bm.book_id
                    """));
            root.put("readingStats", jt().queryForList("""
                    SELECT COALESCE(NULLIF(b.lib_id,''), b.id) AS lib_id,
                           rs.*
                    FROM reading_stats rs JOIN books b ON b.id=rs.book_id
                    """));
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        } catch (Exception e) {
            throw new IllegalStateException("Не вдалося експортувати користувацькі дані: " + e.getMessage(), e);
        }
    }

    @Override
    public Result importFrom(Path file) {
        try {
            Map<String, Object> root = mapper.readValue(file.toFile(), new TypeReference<>() {});
            if (!"MyHomeLib-user-data".equals(root.get("format"))) {
                throw new IllegalArgumentException("Невідомий формат user-data");
            }
            int version = root.containsKey("version") ? num(root.get("version")) : 1;
            if (version < 1 || version > 2) {
                throw new IllegalArgumentException("Непідтримувана версія user-data: " + version);
            }
            int books = 0, groups = 0, bookmarks = 0, progress = 0;

            for (Map<String, Object> r : rows(root, "books")) {
                String id = resolveBookId(r);
                if (id == null) continue;
                books += jt().update("UPDATE books SET rate=?,progress=?,review=? WHERE id=?",
                        num(r.get("rate")), num(r.get("progress")), str(r.get("review")), id);
            }

            for (Map<String, Object> r : rows(root, "groups")) {
                String name = str(r.get("name"));
                String bookId = resolveBookId(r);
                if (name.isBlank() || bookId == null) continue;
                jt().update("INSERT OR IGNORE INTO groups(name,allow_delete) VALUES (?,1)", name);
                groups += jt().update("""
                        INSERT OR IGNORE INTO book_groups(book_id,group_id)
                        SELECT ?,id FROM groups WHERE name=?
                        """, bookId, name);
            }

            for (Map<String, Object> r : rows(root, "readingProgress")) {
                String id = resolveBookId(r);
                if (id == null) continue;
                String anchor = firstNonBlank(str(r.get("anchor_id")), str(r.get("paragraph_id")), "p0");
                String paragraph = firstNonBlank(str(r.get("paragraph_id")), anchor);
                jt().update("""
                        INSERT OR REPLACE INTO reading_progress
                        (book_id,anchor_id,paragraph_index,paragraph_id,char_offset,percent,chapter_title,chapter_id,updated_at,reading_time_seconds)
                        VALUES (?,?,?,?,?,?,?,?,?,?)
                        """, id, anchor, num(r.get("paragraph_index")), paragraph, num(r.get("char_offset")),
                        dbl(r.get("percent")), str(r.get("chapter_title")), str(r.get("chapter_id")),
                        firstNonBlank(str(r.get("updated_at")), LocalDateTime.now().toString()), longNum(r.get("reading_time_seconds")));
                progress++;
            }

            for (Map<String, Object> r : rows(root, "bookmarks")) {
                String bookId = resolveBookId(r);
                if (bookId == null) continue;
                String id = firstNonBlank(str(r.get("id")), UUID.randomUUID().toString());
                String paragraph = firstNonBlank(str(r.get("paragraph_id")), "p0");
                jt().update("""
                        INSERT OR REPLACE INTO bookmarks
                        (id,book_id,paragraph_id,char_offset,position,chapter_title,context,created_at)
                        VALUES (?,?,?,?,?,?,?,?)
                        """, id, bookId, paragraph, num(r.get("char_offset")), dbl(r.get("position")),
                        str(r.get("chapter_title")), str(r.get("context")),
                        firstNonBlank(str(r.get("created_at")), LocalDateTime.now().toString()));
                bookmarks++;
            }

            // Reading history/statistics is part of the user's personal state too.
            for (Map<String, Object> r : rows(root, "readingStats")) {
                String bookId = resolveBookId(r);
                if (bookId == null) continue;
                jt().update("DELETE FROM reading_stats WHERE book_id=?", bookId);
                jt().update("""
                        INSERT INTO reading_stats
                        (book_id,first_read_at,last_read_at,total_reading_seconds,reading_sessions,
                         start_percent,end_percent,current_percent,completed_at)
                        VALUES (?,?,?,?,?,?,?,?,?)
                        """, bookId,
                        firstNonBlank(str(r.get("first_read_at")), LocalDateTime.now().toString()),
                        firstNonBlank(str(r.get("last_read_at")), LocalDateTime.now().toString()),
                        longNum(r.get("total_reading_seconds")), num(r.get("reading_sessions")),
                        num(r.get("start_percent")), num(r.get("end_percent")), num(r.get("current_percent")),
                        nullIfBlank(str(r.get("completed_at"))));
            }

            return new Result(books, groups, bookmarks, progress);
        } catch (Exception e) {
            throw new IllegalStateException("Не вдалося імпортувати користувацькі дані: " + e.getMessage(), e);
        }
    }

    private String resolveBookId(Map<String, Object> row) {
        String libId = firstNonBlank(str(row.get("lib_id")), str(row.get("LibID")));
        String internal = firstNonBlank(str(row.get("internal_id")), str(row.get("book_id")), str(row.get("id")));
        if (!libId.isBlank()) {
            List<String> ids = jt().query("SELECT id FROM books WHERE lib_id=? ORDER BY id", (rs, n) -> rs.getString(1), libId);
            if (ids.size() == 1) return ids.get(0);
            if (ids.size() > 1) {
                // Deterministic conflict policy: use the exported internal id only when it
                // identifies one of the duplicate LibID rows; otherwise skip rather than
                // silently applying personal state to an arbitrary book.
                if (!internal.isBlank() && ids.contains(internal)) return internal;
                log.warn("Пропущено неоднозначний user-data запис: LibID {} відповідає {} книгам", libId, ids.size());
                return null;
            }
        }
        if (!internal.isBlank() && exists(internal)) return internal;
        // Version-1 exports used the internal id as "id"; also allow it to match LibID.
        if (!internal.isBlank()) {
            List<String> ids = jt().query("SELECT id FROM books WHERE lib_id=? LIMIT 1", (rs, n) -> rs.getString(1), internal);
            if (!ids.isEmpty()) return ids.get(0);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> root, String key) {
        Object v = root.get(key);
        return v instanceof List<?> l ? (List<Map<String, Object>>) (List<?>) l : List.of();
    }

    private boolean exists(String id) {
        Integer n = jt().queryForObject("SELECT COUNT(*) FROM books WHERE id=?", Integer.class, id);
        return n != null && n > 0;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String nullIfBlank(String s) { return s == null || s.isBlank() ? null : s; }
    private static int num(Object o) { if (o instanceof Number n) return n.intValue(); try { return Integer.parseInt(str(o)); } catch (Exception e) { return 0; } }
    private static long longNum(Object o) { if (o instanceof Number n) return n.longValue(); try { return Long.parseLong(str(o)); } catch (Exception e) { return 0L; } }
    private static double dbl(Object o) { if (o instanceof Number n) return n.doubleValue(); try { return Double.parseDouble(str(o)); } catch (Exception e) { return 0d; } }
    private static String firstNonBlank(String... values) { for (String v : values) if (v != null && !v.isBlank()) return v; return ""; }
}
