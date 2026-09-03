package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.port.out.repository.NavigationFacetRepository;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.domain.model.collection.CollectionType;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.AuthorSearchNameNormalizer;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookFilterSqlAdapter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
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
        return findAuthorsPage(initial, filter, Integer.MAX_VALUE, 0).content();
    }

    @Override
    public FacetPage findAuthorsPage(char initial, BookFilterSpec filter, int limit, int offset) {
        BookFilterSqlAdapter.FilterSql f = BookFilterSqlAdapter.build(filter, "b");
        AuthorInitialSql initialSql = authorInitialSql(initial);
        List<Object> baseParams = new ArrayList<>(initialSql.params());
        baseParams.addAll(f.params());

        String fromWhere = """
                FROM authors a
                JOIN book_authors ba ON ba.author_id = a.id
                JOIN books b ON b.id = ba.book_id
                WHERE b.deleted = 0
                  AND %s
                  %s
                """.formatted(initialSql.condition(), andFilter(f));

        Long total = jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM (
                    SELECT a.id
                    %s
                    GROUP BY a.id
                ) matched_authors
                """.formatted(fromWhere), Long.class, baseParams.toArray());

        int safeLimit = Math.max(1, limit);
        int safeOffset = Math.max(0, offset);
        List<Object> dataParams = new ArrayList<>(baseParams);
        dataParams.add(safeLimit);
        dataParams.add(safeOffset);

        String sql = """
                SELECT a.id,
                       a.first_name,
                       a.middle_name,
                       a.last_name,
                       COUNT(DISTINCT b.id) AS book_count
                %s
                GROUP BY a.id, a.first_name, a.middle_name, a.last_name
                ORDER BY COALESCE(a.last_name, '') COLLATE NOCASE,
                         COALESCE(a.first_name, '') COLLATE NOCASE,
                         COALESCE(a.middle_name, '') COLLATE NOCASE,
                         a.id
                LIMIT ? OFFSET ?
                """.formatted(fromWhere);

        List<Facet> content = jdbc().query(sql, (rs, rowNum) -> new Facet(
                rs.getString("id"),
                fullName(rs.getString("last_name"), rs.getString("first_name"), rs.getString("middle_name")),
                rs.getLong("book_count")), dataParams.toArray());
        return new FacetPage(content, total == null ? 0 : total);
    }

    @Override
    public AuthorFacetSlice findAuthorsAfter(char initial, BookFilterSpec filter, int limit, AuthorCursor after) {
        BookFilterSqlAdapter.FilterSql f = BookFilterSqlAdapter.build(filter, "b");
        AuthorInitialSql initialSql = authorInitialSql(initial);
        int safeLimit = Math.max(1, Math.min(limit, 1000));

        String cursorCondition = after == null ? "" : """
                  AND (
                        COALESCE(a.last_name, '') COLLATE NOCASE,
                        COALESCE(a.first_name, '') COLLATE NOCASE,
                        COALESCE(a.middle_name, '') COLLATE NOCASE,
                        a.id
                      ) > (?, ?, ?, ?)
                """;

        // Do not GROUP every author in the selected initial before LIMIT is applied.
        // The author table is traversed in cursor order and only the bounded page is
        // checked/count-aggregated against matching books. This keeps first and deep
        // pages proportional to page size instead of the size of the whole initial.
        String matchingBookFilter = andFilter(f);
        String sql = """
                SELECT a.id,
                       a.first_name,
                       a.middle_name,
                       a.last_name,
                       (
                           SELECT COUNT(*)
                           FROM book_authors count_ba
                           JOIN books b ON b.id = count_ba.book_id
                           WHERE count_ba.author_id = a.id
                             AND b.deleted = 0
                             %s
                       ) AS book_count
                FROM authors a
                WHERE %s
                  %s
                  AND EXISTS (
                      SELECT 1
                      FROM book_authors exists_ba
                      JOIN books b ON b.id = exists_ba.book_id
                      WHERE exists_ba.author_id = a.id
                        AND b.deleted = 0
                        %s
                  )
                ORDER BY COALESCE(a.last_name, '') COLLATE NOCASE,
                         COALESCE(a.first_name, '') COLLATE NOCASE,
                         COALESCE(a.middle_name, '') COLLATE NOCASE,
                         a.id
                LIMIT ?
                """.formatted(matchingBookFilter, initialSql.condition(), cursorCondition, matchingBookFilter);

        // SQL placeholder order matters: SELECT/count subquery comes before the
        // outer WHERE, then EXISTS. The filter therefore intentionally appears twice.
        List<Object> dataParams = new ArrayList<>(f.params());
        dataParams.addAll(initialSql.params());
        if (after != null) {
            dataParams.add(after.lastName());
            dataParams.add(after.firstName());
            dataParams.add(after.middleName());
            dataParams.add(after.id());
        }
        dataParams.addAll(f.params());
        dataParams.add(safeLimit + 1);

        List<AuthorFacetRow> rows = jdbc().query(sql, (rs, rowNum) -> {
            String id = rs.getString("id");
            String firstName = nullToEmpty(rs.getString("first_name"));
            String middleName = nullToEmpty(rs.getString("middle_name"));
            String lastName = nullToEmpty(rs.getString("last_name"));
            return new AuthorFacetRow(
                    new Facet(id, fullName(lastName, firstName, middleName), rs.getLong("book_count")),
                    new AuthorCursor(lastName, firstName, middleName, id));
        }, dataParams.toArray());

        boolean hasMore = rows.size() > safeLimit;
        List<AuthorFacetRow> pageRows = hasMore ? rows.subList(0, safeLimit) : rows;
        List<Facet> content = pageRows.stream().map(AuthorFacetRow::facet).toList();
        AuthorCursor nextCursor = hasMore && !pageRows.isEmpty()
                ? pageRows.getLast().cursor()
                : null;

        // Exact totals force a full aggregation of the initial and used to roughly
        // double first-page latency on 500k+ catalogues. Navigation only needs
        // content + hasMore/cursor, so keep total optional and out of the hot path.
        return new AuthorFacetSlice(content, OptionalLong.empty(), nextCursor);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record AuthorFacetRow(Facet facet, AuthorCursor cursor) { }

    private AuthorInitialSql authorInitialSql(char initial) {
        List<Object> params = new ArrayList<>();
        String condition;
        if (initial == '#') {
            String supported = TOOLBAR_INITIALS + TOOLBAR_INITIALS.toLowerCase(Locale.ROOT);
            String placeholders = String.join(",", java.util.Collections.nCopies(supported.length(), "?"));
            condition = "SUBSTR((" + AUTHOR_DISPLAY_EXPR + "), 1, 1) NOT IN (" + placeholders + ") " +
                    "AND SUBSTR((" + AUTHOR_DISPLAY_EXPR + "), 1, 1) <> ''";
            supported.chars().forEach(c -> params.add(String.valueOf((char) c)));
        } else {
            condition = "SUBSTR((" + AUTHOR_DISPLAY_EXPR + "), 1, 1) IN (?, ?)";
            params.add(String.valueOf(Character.toUpperCase(initial)));
            params.add(String.valueOf(Character.toLowerCase(initial)));
        }
        return new AuthorInitialSql(condition, List.copyOf(params));
    }

    private record AuthorInitialSql(String condition, List<Object> params) {}

    @Override
    public List<Facet> searchAuthors(String query, BookFilterSpec filter, int limit) {
        if (query == null || query.isBlank()) return List.of();
        BookFilterSqlAdapter.FilterSql f = BookFilterSqlAdapter.build(filter, "b");
        String normalizedPattern = "%" + escapeLike(AuthorSearchNameNormalizer.normalizeQuery(query)) + "%";
        String originalPattern = "%" + escapeLike(query.trim()) + "%";
        List<Object> params = new ArrayList<>();
        // SQLite LOWER()/NOCASE are ASCII-only without ICU. Author search_name is
        // normalized in Java, so it is the authoritative Unicode-aware search key.
        params.add(normalizedPattern);
        // Fallback keeps legacy rows searchable until their search_name backfill runs.
        params.add(originalPattern);
        params.addAll(f.params());
        params.add(Math.max(1, Math.min(limit, 500)));

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
                  AND (
                        COALESCE(a.search_name, '') LIKE ? ESCAPE '\\'
                     OR TRIM(COALESCE(a.last_name, '') || ' ' || COALESCE(a.first_name, '') || ' ' || COALESCE(a.middle_name, '')) LIKE ? ESCAPE '\\'
                  )
                  %s
                GROUP BY a.id, a.first_name, a.middle_name, a.last_name
                ORDER BY COALESCE(a.last_name, '') COLLATE NOCASE,
                         COALESCE(a.first_name, '') COLLATE NOCASE,
                         COALESCE(a.middle_name, '') COLLATE NOCASE,
                         a.id
                LIMIT ?
                """.formatted(andFilter(f));
        return jdbc().query(sql, (rs, rowNum) -> new Facet(
                rs.getString("id"),
                fullName(rs.getString("last_name"), rs.getString("first_name"), rs.getString("middle_name")),
                rs.getLong("book_count")), params.toArray());
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    public List<Facet> findDownloadedAuthors(BookFilterSpec filter) {
        var collection = collectionManager.getCurrentCollection();
        if (collection == null) return List.of();
        CollectionType type = CollectionType.fromCode(collection.getType());
        boolean online = type == CollectionType.REMOTE || type == CollectionType.GENERIC_REMOTE
                || (collection.getUrl() != null && !collection.getUrl().isBlank())
                || (collection.getConnectionScript() != null && !collection.getConnectionScript().isBlank());
        if (!online) return List.of();
        BookFilterSqlAdapter.FilterSql f = BookFilterSqlAdapter.build(filter, "b");
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
                  AND COALESCE(b.local, 0) = 1
                  %s
                GROUP BY a.id, a.first_name, a.middle_name, a.last_name
                ORDER BY COALESCE(a.last_name, '') COLLATE NOCASE,
                         COALESCE(a.first_name, '') COLLATE NOCASE,
                         COALESCE(a.middle_name, '') COLLATE NOCASE,
                         a.id
                """.formatted(andFilter(f));
        return jdbc().query(sql, (rs, rowNum) -> new Facet(
                rs.getString("id"),
                fullName(rs.getString("last_name"), rs.getString("first_name"), rs.getString("middle_name")),
                rs.getLong("book_count")), f.params().toArray());
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
        String normalizedLanguage = BookFilterSqlAdapter.normalizedLanguageExpression("b");
        String sql = """
                SELECT %s AS facet_id,
                       %s AS facet_label,
                       COUNT(*) AS book_count
                FROM books b
                WHERE b.deleted = 0
                  AND %s <> ''
                  %s
                GROUP BY %s
                ORDER BY %s
                """.formatted(normalizedLanguage, normalizedLanguage, normalizedLanguage,
                andFilter(f), normalizedLanguage, normalizedLanguage);
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
                SELECT k.normalized_name AS facet_id,
                       k.display_name AS facet_label,
                       COUNT(DISTINCT b.id) AS book_count
                FROM keywords k
                JOIN keyword_books kb ON kb.normalized_name = k.normalized_name
                JOIN books b ON b.id = kb.book_id
                WHERE b.deleted = 0
                  %s
                GROUP BY k.normalized_name, k.display_name
                ORDER BY k.normalized_name
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