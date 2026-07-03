package com.myhomelibcorp.infrastructure.service;

import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Repository
@Slf4j
public class GenreServiceImpl implements GenreRepository {

    private final Map<String, String> genreMap = new LinkedHashMap<>();
    private final JdbcTemplate jdbcTemplate;

    public GenreServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        loadGenresFromResource();
        loadGenresFromDatabase();
    }

    private void loadGenresFromResource() {
        try {
            ClassPathResource resource = new ClassPathResource("genres_fb2.txt");
            log.info("Завантаження жанрів з: {}", resource.getPath());
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int semicolonIdx = line.indexOf(';');
                    String code, name;
                    if (semicolonIdx >= 0) {
                        String leftPart = line.substring(0, semicolonIdx).trim();
                        name = line.substring(semicolonIdx + 1).trim();
                        if (name.isEmpty()) continue;
                        String[] tokens = leftPart.split("\\s+");
                        code = tokens.length > 0 ? tokens[tokens.length - 1] : leftPart;
                    } else {
                        int firstSpace = line.indexOf(' ');
                        if (firstSpace < 0) continue;
                        code = line.substring(0, firstSpace).trim();
                        name = line.substring(firstSpace + 1).trim();
                    }
                    if (!code.isEmpty() && !name.isEmpty()) {
                        genreMap.put(code, name);
                        genreMap.put(code.replace(".", ""), name);
                        count++;
                    }
                }
                log.info("Завантажено {} жанрів з ресурсу", count);
            }
        } catch (Exception e) {
            log.error("Помилка завантаження жанрів", e);
        }
    }

    private void loadGenresFromDatabase() {
        try {
            String sql = "SELECT code, name FROM genres";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            for (Map<String, Object> row : rows) {
                String code = (String) row.get("code");
                String name = (String) row.get("name");
                if (code != null && name != null && !genreMap.containsKey(code)) {
                    genreMap.put(code, name);
                }
            }
            log.info("Додано {} жанрів з БД", rows.size());
        } catch (Exception e) {
            log.warn("Не вдалося завантажити жанри з БД", e);
        }
    }

    @Override
    public String getGenreName(String code) {
        if (code == null) return "";
        String name = genreMap.get(code);
        if (name != null) return name;
        name = genreMap.get(code.replace(".", ""));
        if (name != null) return name;
        log.warn("Жанр з кодом '{}' не знайдено", code);
        return code;
    }

    @Override
    public List<String> getAllGenreNames() {
        return new ArrayList<>(genreMap.values());
    }

    @Override
    public Map<String, String> getAllGenres() {
        return new LinkedHashMap<>(genreMap);
    }

    @Override
    public List<String> getAllGenreCodes() {
        return new ArrayList<>(genreMap.keySet());
    }

    @Override
    public List<Genre> getAllGenresHierarchy() {
        try {
            String sql = "SELECT code, name, parent_code, fb2_code FROM genres";
            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                GenreId id = GenreId.fromCode(rs.getString("code"));
                GenreId parentId = rs.getString("parent_code") != null
                        ? GenreId.fromCode(rs.getString("parent_code"))
                        : null;
                return new Genre(id, rs.getString("name"), parentId, rs.getString("fb2_code"));
            });
        } catch (Exception e) {
            log.warn("Не вдалося завантажити ієрархію жанрів", e);
            return List.of();
        }
    }

    @Override
    public List<Genre> findAll() {
        return getAllGenresHierarchy();
    }

    @Override
    public Optional<Genre> findById(GenreId id) {
        try {
            String sql = "SELECT code, name, parent_code, fb2_code FROM genres WHERE code = ?";
            Genre genre = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
                GenreId gid = GenreId.fromCode(rs.getString("code"));
                GenreId parentId = rs.getString("parent_code") != null
                        ? GenreId.fromCode(rs.getString("parent_code"))
                        : null;
                return new Genre(gid, rs.getString("name"), parentId, rs.getString("fb2_code"));
            }, id.asString());
            return Optional.of(genre);
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
        jdbcTemplate.update(sql,
                genre.getId().asString(),
                genre.getName(),
                genre.getParentId() != null ? genre.getParentId().asString() : null,
                genre.getFb2Code()
        );
        return genre;
    }

    @Override
    public void deleteById(GenreId id) {
        jdbcTemplate.update("DELETE FROM genres WHERE code = ?", id.asString());
    }
}