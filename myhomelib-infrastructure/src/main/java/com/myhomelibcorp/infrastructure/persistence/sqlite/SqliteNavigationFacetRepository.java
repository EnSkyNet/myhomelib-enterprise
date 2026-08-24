package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.NavigationFacetRepository;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** SQLite aggregation queries backing Stage 3/4 navigation facets. */
@Repository
public class SqliteNavigationFacetRepository implements NavigationFacetRepository {

    private final CollectionManager collectionManager;

    public SqliteNavigationFacetRepository(CollectionManager collectionManager) {
        this.collectionManager = collectionManager;
    }

    private JdbcTemplate jdbc() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    @Override
    public List<Facet> findYears() {
        String sql = """
                SELECT CAST(year AS TEXT) AS facet_id,
                       CAST(year AS TEXT) AS facet_label,
                       COUNT(*) AS book_count
                FROM books
                WHERE deleted = 0
                  AND year IS NOT NULL
                  AND year > 0
                GROUP BY year
                ORDER BY year DESC
                """;
        return queryFacets(sql);
    }

    @Override
    public List<Facet> findLanguages() {
        String sql = """
                SELECT LOWER(TRIM(language)) AS facet_id,
                       LOWER(TRIM(language)) AS facet_label,
                       COUNT(*) AS book_count
                FROM books
                WHERE deleted = 0
                  AND language IS NOT NULL
                  AND TRIM(language) <> ''
                GROUP BY LOWER(TRIM(language))
                ORDER BY LOWER(TRIM(language))
                """;
        return queryFacets(sql);
    }

    @Override
    public List<ArchiveFacet> findArchives() {
        String sql = """
                SELECT MIN(COALESCE(collection_root, '')) AS collection_root,
                       MIN(folder) AS archive_path,
                       COUNT(*) AS book_count
                FROM books
                WHERE deleted = 0
                  AND COALESCE(archive_entry, '') <> ''
                  AND folder IS NOT NULL
                  AND TRIM(folder) <> ''
                GROUP BY LOWER(REPLACE(COALESCE(collection_root, ''), '\\', '/')),
                         LOWER(REPLACE(folder, '\\', '/'))
                ORDER BY LOWER(REPLACE(folder, '\\', '/'))
                """;
        return jdbc().query(sql, (rs, rowNum) -> new ArchiveFacet(
                rs.getString("collection_root"),
                rs.getString("archive_path"),
                rs.getLong("book_count")));
    }

    @Override
    public List<Facet> findKeywords() {
        // INPX/FB2 metadata in the existing catalogue is a flat text field. Split the
        // common separators in SQLite so navigation never materializes all books.
        String sql = """
                WITH RECURSIVE keyword_source(book_id, rest) AS (
                    SELECT id,
                           REPLACE(REPLACE(COALESCE(keywords, ''), ';', ','), '|', ',') || ','
                    FROM books
                    WHERE deleted = 0
                      AND keywords IS NOT NULL
                      AND TRIM(keywords) <> ''
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
                """;
        return queryFacets(sql);
    }

    @Override
    public List<Facet> findGroups() {
        String sql = """
                SELECT CAST(g.id AS TEXT) AS facet_id,
                       g.name AS facet_label,
                       COUNT(b.id) AS book_count
                FROM groups g
                LEFT JOIN book_groups bg ON bg.group_id = g.id
                LEFT JOIN books b ON b.id = bg.book_id AND b.deleted = 0
                GROUP BY g.id, g.name
                ORDER BY LOWER(g.name), g.id
                """;
        return queryFacets(sql);
    }

    @Override
    public List<Facet> findReviewSubsets() {
        String sql = """
                SELECT 'rated' AS facet_id, 'rated' AS facet_label,
                       SUM(CASE WHEN deleted = 0 AND COALESCE(rate, 0) > 0 THEN 1 ELSE 0 END) AS book_count
                FROM books
                UNION ALL
                SELECT 'reviewed', 'reviewed',
                       SUM(CASE WHEN deleted = 0 AND review IS NOT NULL AND TRIM(review) <> '' THEN 1 ELSE 0 END)
                FROM books
                UNION ALL
                SELECT 'rated-reviewed', 'rated-reviewed',
                       SUM(CASE WHEN deleted = 0 AND COALESCE(rate, 0) > 0
                                      AND review IS NOT NULL AND TRIM(review) <> '' THEN 1 ELSE 0 END)
                FROM books
                """;
        return queryFacets(sql);
    }

    private List<Facet> queryFacets(String sql) {
        return jdbc().query(sql, (rs, rowNum) -> new Facet(
                rs.getString("facet_id"),
                rs.getString("facet_label"),
                rs.getLong("book_count")));
    }
}
