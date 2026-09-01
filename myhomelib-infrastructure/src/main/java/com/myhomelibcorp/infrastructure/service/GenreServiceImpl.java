package com.myhomelibcorp.infrastructure.service;

import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * Collection-backed genre repository.
 *
 * <p>Genre display names are intentionally NOT loaded from a bundled
 * {@code genres_fb2.txt}. The canonical/localized FB2 dictionary lives in
 * {@code Lang/<language>.json}; UI code resolves stable genre codes through
 * {@code LanguageCatalogService}. The database stores only collection/source
 * data and stable codes.</p>
 */
@Repository
@Slf4j
public class GenreServiceImpl implements GenreRepository {

    private final CollectionManager collectionManager;

    public GenreServiceImpl(CollectionManager collectionManager) {
        this.collectionManager = collectionManager;
    }

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    @Override
    public String getGenreName(String code) {
        if (code == null || code.isBlank()) return "";
        try {
            String dbName = getJdbcTemplate().queryForObject(
                    "SELECT name FROM genres WHERE code = ?", String.class, code);
            return isMeaningfulSourceName(code, dbName) ? dbName.trim() : code.trim();
        } catch (Exception ignored) {
            return code.trim();
        }
    }

    @Override
    public List<String> getAllGenreNames() {
        return loadCollectionGenres().values().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(v -> !v.isBlank())
                .distinct()
                .toList();
    }

    @Override
    public Map<String, String> getAllGenres() {
        return loadCollectionGenres();
    }

    @Override
    public List<String> getAllGenreCodes() {
        return new ArrayList<>(loadCollectionGenres().keySet());
    }

    private Map<String, String> loadCollectionGenres() {
        try {
            Map<String, String> result = new LinkedHashMap<>();
            getJdbcTemplate().query(
                    "SELECT code, name FROM genres ORDER BY LOWER(COALESCE(name, code)), code",
                    rs -> {
                        String code = rs.getString("code");
                        if (code == null || code.isBlank()) return;
                        String name = rs.getString("name");
                        result.put(code, isMeaningfulSourceName(code, name) ? name.trim() : code.trim());
                    });
            return result;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static boolean isMeaningfulSourceName(String code, String name) {
        return name != null && !name.isBlank() && !name.trim().equalsIgnoreCase(code.trim());
    }

    @Override
    public List<Genre> getAllGenresHierarchy() {
        try {
            String sql = "SELECT code, name, parent_code, fb2_code FROM genres";
            return getJdbcTemplate().query(sql, (rs, rowNum) -> {
                GenreId id = GenreId.fromCode(rs.getString("code"));
                GenreId parentId = rs.getString("parent_code") != null
                        ? GenreId.fromCode(rs.getString("parent_code"))
                        : null;
                return new Genre(id, rs.getString("name"), parentId, rs.getString("fb2_code"));
            });
        } catch (Exception e) {
            log.warn("Не вдалося завантажити ієрархію жанрів: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<Genre> findAll() {
        return getAllGenresHierarchy();
    }

    @Override
    public List<Genre> searchByName(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        String sql = """
                SELECT code, name, parent_code, fb2_code
                  FROM genres
                 WHERE LOWER(name) LIKE ? OR LOWER(code) LIKE ?
                 ORDER BY LOWER(name), code
                 LIMIT ?
                """;
        return getJdbcTemplate().query(sql, (rs, rowNum) -> {
            GenreId id = GenreId.fromCode(rs.getString("code"));
            GenreId parentId = rs.getString("parent_code") != null
                    ? GenreId.fromCode(rs.getString("parent_code")) : null;
            return new Genre(id, rs.getString("name"), parentId, rs.getString("fb2_code"));
        }, pattern, pattern, Math.min(limit, 200));
    }

    @Override
    public Optional<Genre> findById(GenreId id) {
        try {
            String sql = "SELECT code, name, parent_code, fb2_code FROM genres WHERE code = ?";
            Genre genre = getJdbcTemplate().queryForObject(sql, (rs, rowNum) -> {
                GenreId gid = GenreId.fromCode(rs.getString("code"));
                GenreId parentId = rs.getString("parent_code") != null
                        ? GenreId.fromCode(rs.getString("parent_code"))
                        : null;
                return new Genre(gid, rs.getString("name"), parentId, rs.getString("fb2_code"));
            }, id.asString());
            return Optional.ofNullable(genre);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Genre save(Genre genre) {
        String sql = """
            INSERT INTO genres (code, name, parent_code, fb2_code)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(code) DO UPDATE SET
                name = excluded.name,
                parent_code = excluded.parent_code,
                fb2_code = excluded.fb2_code
            """;
        getJdbcTemplate().update(sql,
                genre.getId().asString(),
                genre.getName(),
                genre.getParentId() != null ? genre.getParentId().asString() : null,
                genre.getFb2Code()
        );
        return genre;
    }

    @Override
    public void deleteById(GenreId id) {
        getJdbcTemplate().update("DELETE FROM genres WHERE code = ?", id.asString());
    }

    @Override
    public long countOrphanedGenres() {
        try {
            String sql = """
                    SELECT COUNT(*) FROM genres g
                    WHERE NOT EXISTS (
                        SELECT 1 FROM book_genres bg WHERE bg.genre_code = g.code
                    )
                    """;
            Long value = getJdbcTemplate().queryForObject(sql, Long.class);
            return value == null ? 0L : value;
        } catch (Exception e) {
            log.warn("Не вдалося підрахувати кількість жанрів без книг: {}", e.getMessage());
            return 0;
        }
    }
}
