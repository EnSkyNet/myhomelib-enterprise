package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.port.out.repository.NavigationFacetRepository;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookFilterSqlAdapter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** SQLite aggregation queries backing navigation facets and Stage 8 unified filters. */
@Repository
public class SqliteNavigationFacetRepository implements NavigationFacetRepository {

    private static final String AUTHOR_DISPLAY_EXPR = """
            CASE
                WHEN TRIM(COALESCE(a.last_name, '')) <> '' THEN TRIM(a.last_name)
                WHEN TRIM(COALESCE(a.first_name, '')) <> '' THEN TRIM(a.first_name)
                ELSE TRIM(COALESCE(a.middle_name, ''))
            END
            """;
    private static final String TOOLBAR_INITIALS =
            "АБВГҐДЕЄЁЖЗИІЇЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final CollectionManager collectionManager;

    public SqliteNavigationFacetRepository(CollectionManager collectionManager) {
        this.collectionManager = collectionManager;
    }

    private JdbcTemplate jdbc() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    @Override
    public List<Facet> findAuthors(char initial, BookFilterSpec filter) {
        BookFilterSqlAdapter.FilterSql f = BookFilterSqlAdapter.build(filter, "b");
        List<Object> params = new ArrayList<>();
        String initialCondition;
        if (initial == '#') {
            String supported = TOOLBAR_INITIALS + TOOLBAR_INITIALS.toLowerCase(Locale.ROOT);
            String placeholders = String.join(",", java.util.Collections.nCopies(supported.length(), "?"));
            initialCondition = "SUBSTR((" + AUTHOR_DISPLAY_EXPR + "), 1, 1) NOT IN (" + placeholders + ") " +
                    "AND SUBSTR((" + AUTHOR_DISPLAY_EXPR + "), 1, 1) <> ''";
            supported.chars().forEach(c -> params.add(String.valueOf((char) c)));
        } else {
            initialCondition = "SUBSTR((" + AUTHOR_DISPLAY_EXPR + "), 1, 1) IN (?, ?)";
            params.add(String.valueOf(Character.toUpperCase(initial)));
            params.add(String.valueOf(Character.toLowerCase(initial)));
        }
        params.addAll(f.params());

        String sql = """
                SELECT a.id,
                       a.first_name,
                       a.middle_name,
                       a.last_name,
                       COUNT(DISTINCT b.id) AS book_count
                FROM authors a
                JOIN book_authors ba ON ba.author_id = a.id
                JOIN books b ON b.id = ba.book_id
                WHERE b.deleted = 0
                  AND %s
                  %s
                GROUP BY a.id, a.first_name, a.middle_name, a.last_name
                ORDER BY COALESCE(a.last_name, '') COLLATE NOCASE,
                         COALESCE(a.first_name, '') COLLATE NOCASE,
                         COALESCE(a.middle_name, '') COLLATE NOCASE,
                         a.id
                """.formatted(initialCondition, andFilter(f));

        return jdbc().query(sql, (rs, rowNum) -> new Facet(
                rs.getString("id"),
                fullName(rs.getString("last_name"), rs.getString("first_name"), rs.getString("middle_name")),
                rs.getLong("book_count")), params.toArray());
    }

    @Override
    public Optional<Character> findFirstAuthorInitial(BookFilterSpec filter) {
        BookFilterSqlAdapter.FilterSql f = BookFilterSqlAdapter.build(filter, "b");
        List<Object> params = new ArrayList<>(f.params());
        String sql = """
                SELECT DISTINCT SUBSTR((%s), 1, 1) AS initial
                FROM authors a
                JOIN book_authors ba ON ba.author_id = a.id
                JOIN books b ON b.id = ba.book_id
                WHERE b.deleted = 0
                  AND TRIM((%s)) <> ''
                  %s
                """.formatted(AUTHOR_DISPLAY_EXPR, AUTHOR_DISPLAY_EXPR, andFilter(f));
        List<String> values = jdbc().query(sql, (rs, rowNum) -> rs.getString("initial"), params.toArray());
        Set<Character> available = new HashSet<>();
        boolean other = false;
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            char c = value.charAt(0);
            char upper = Character.toUpperCase(c);
            if (Character.isLetter(c) && TOOLBAR_INITIALS.indexOf(upper) >= 0) available.add(upper);
            else other = true;
        }
        for (char candidate : TOOLBAR_INITIALS.toCharArray()) {
            if (available.contains(candidate)) return Optional.of(candidate);
        }
        return other ? Optional.of('#') : Optional.empty();
    }

    @Override
    public List<Facet> findSeries(BookFilterSpec filter) {
        BookFilterSqlAdapter.FilterSql f = BookFilterSqlAdapter.build(filter, "b");
        String sql = """
                SELECT s.id AS facet_id, s.name AS facet_label, COUNT(DISTINCT b.id) AS book_count
                FROM series s
                JOIN books b ON LOWER(TRIM(COALESCE(b.series, ''))) = LOWER(TRIM(s.name))
                WHERE b.deleted = 0
                  %s
                GROUP BY s.id, s.name
                ORDER BY LOWER(s.name), s.id
                """.formatted(andFilter(f));
        return queryFacets(sql, f.params());
    }

    @Override
    public List<Facet> findGenres(BookFilterSpec filter) {
        BookFilterSqlAdapter.FilterSql f = BookFilterSqlAdapter.build(filter, "b");
        String sql = """
                SELECT g.code AS facet_id, COALESCE(NULLIF(TRIM(g.name), ''), g.code) AS facet_label,
                       COUNT(DISTINCT b.id) AS book_count
                FROM genres g
                JOIN book_genres bg ON bg.genre_code = g.code
                JOIN books b ON b.id = bg.book_id
                WHERE b.deleted = 0
                  %s
                GROUP BY g.code, g.name
                ORDER BY LOWER(COALESCE(NULLIF(TRIM(g.name), ''), g.code)), g.code
                """.formatted(andFilter(f));
        return queryFacets(sql, f.params());
    }

    @Override
    public List<Facet> findYears(BookFilterSpec filter) {
        BookFilterSqlAdapter.FilterSql f = BookFilterSqlAdapter.build(filter, "b");
        String sql = """
                SELECT CAST(b.year AS TEXT) AS facet_id,
                       CAST(b.year AS TEXT) AS facet_label,
                       COUNT(*) AS book_count
                FROM books b
                WHERE b.deleted = 0
                  AND b.year IS NOT NULL
                  AND b.year > 0
                  %s
                GROUP BY b.year
                ORDER BY b.year DESC
                """.formatted(andFilter(f));
        return queryFacets(sql, f.params());
    }

    @Override
    public List<Facet> findLanguages(BookFilterSpec filter) {
        BookFilterSqlAdapter.FilterSql f = BookFilterSqlAdapter.build(filter, "b");
        String sql = """
                SELECT LOWER(TRIM(b.language)) AS facet_id,
                       LOWER(TRIM(b.language)) AS facet_label,
                       COUNT(*) AS book_count
                FROM books b
                WHERE b.deleted = 0
                  AND b.language IS NOT NULL
                  AND TRIM(b.language) <> ''
                  %s
                GROUP BY LOWER(TRIM(b.language))
                ORDER BY LOWER(TRIM(b.language))
                """.formatted(andFilter(f));
        return queryFacets(sql, f.params());
    }

    @Override
    public List<ArchiveFacet> findArchives(BookFilterSpec filter) {
        BookFilterSqlAdapter.FilterSql f = BookFilterSqlAdapter.build(filter, "b");
        String sql = """
                SELECT MIN(COALESCE(b.collection_root, '')) AS collection_root,
                       MIN(b.folder) AS archive_path,
                       COUNT(*) AS book_count
                FROM books b
                WHERE b.deleted = 0
                  AND COALESCE(b.archive_entry, '') <> ''
                  AND b.folder IS NOT NULL
                  AND TRIM(b.folder) <> ''
                  %s
                GROUP BY LOWER(REPLACE(COALESCE(b.collection_root, ''), '\\', '/')),
                         LOWER(REPLACE(b.folder, '\\', '/'))
                ORDER BY LOWER(REPLACE(b.folder, '\\', '/'))
                """.formatted(andFilter(f));
        return jdbc().query(sql, (rs, rowNum) -> new ArchiveFacet(
                rs.getString("collection_root"),
                rs.getString("archive_path"),
                rs.getLong("book_count")), f.params().toArray());
    }

    @Override
    public List<Facet> findKeywords(BookFilterSpec filter) {
        BookFilterSqlAdapter.FilterSql f = BookFilterSqlAdapter.build(filter, "b");
        String sql = """
                WITH RECURSIVE keyword_source(book_id, rest) AS (
                    SELECT b.id,
                           REPLACE(REPLACE(COALESCE(b.keywords, ''), ';', ','), '|', ',') || ','
                    FROM books b
                    WHERE b.deleted = 0
                      AND b.keywords IS NOT NULL
                      AND TRIM(b.keywords) <> ''
                      %s
                ),
                split(book_id, token, rest) AS (
                    SELECT book_id, '', rest FROM keyword_source
                    UNION ALL
                    SELECT book_id,
                           TRIM(SUBSTR(rest, 1, INSTR(rest, ',') - 1)),
                           SUBSTR(rest, INSTR(rest, ',') + 1)
                    FROM split
                    WHERE rest <> ''
                )
                SELECT LOWER(token) AS facet_id,
                       MIN(token) AS facet_label,
                       COUNT(DISTINCT book_id) AS book_count
                FROM split
                WHERE token <> ''
                GROUP BY LOWER(token)
                ORDER BY LOWER(token)
                """.formatted(andFilter(f));
        return queryFacets(sql, f.params());
    }

    @Override
    public List<Facet> findGroups(BookFilterSpec filter) {
        BookFilterSqlAdapter.FilterSql f = BookFilterSqlAdapter.build(filter, "b");
        String joinFilter = f.isEmpty() ? "" : " AND " + f.clause();
        String sql = """
                SELECT CAST(g.id AS TEXT) AS facet_id,
                       g.name AS facet_label,
                       COUNT(DISTINCT b.id) AS book_count
                FROM groups g
                LEFT JOIN book_groups bg ON bg.group_id = g.id
                LEFT JOIN books b ON b.id = bg.book_id AND b.deleted = 0%s
                GROUP BY g.id, g.name
                ORDER BY LOWER(g.name), g.id
                """.formatted(joinFilter);
        return queryFacets(sql, f.params());
    }

    @Override
    public List<Facet> findReviewSubsets(BookFilterSpec filter) {
        return List.of(
                new Facet("rated", "rated", countSubset(filter, "COALESCE(b.rate, 0) > 0")),
                new Facet("reviewed", "reviewed", countSubset(filter, "b.review IS NOT NULL AND TRIM(b.review) <> ''")),
                new Facet("rated-reviewed", "rated-reviewed",
                        countSubset(filter, "COALESCE(b.rate, 0) > 0 AND b.review IS NOT NULL AND TRIM(b.review) <> ''"))
        );
    }

    private long countSubset(BookFilterSpec filter, String subset) {
        BookFilterSqlAdapter.FilterSql f = BookFilterSqlAdapter.build(filter, "b");
        String sql = "SELECT COUNT(*) FROM books b WHERE b.deleted = 0 AND (" + subset + ") " + andFilter(f);
        Long count = jdbc().queryForObject(sql, Long.class, f.params().toArray());
        return count == null ? 0L : count;
    }

    private List<Facet> queryFacets(String sql, List<Object> params) {
        return jdbc().query(sql, (rs, rowNum) -> new Facet(
                rs.getString("facet_id"),
                rs.getString("facet_label"),
                rs.getLong("book_count")), params.toArray());
    }

    private static String andFilter(BookFilterSqlAdapter.FilterSql filter) {
        return filter == null || filter.isEmpty() ? "" : "AND " + filter.clause();
    }

    private static String fullName(String last, String first, String middle) {
        StringBuilder out = new StringBuilder();
        append(out, last); append(out, first); append(out, middle);
        return out.toString();
    }

    private static void append(StringBuilder out, String value) {
        if (value == null || value.isBlank()) return;
        if (!out.isEmpty()) out.append(' ');
        out.append(value.trim());
    }
}
