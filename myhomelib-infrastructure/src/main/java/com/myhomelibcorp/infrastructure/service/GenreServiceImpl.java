package com.myhomelibcorp.infrastructure.service;

import com.myhomelibcorp.application.port.out.GenreService;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@Slf4j
public class GenreServiceImpl implements GenreService {

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
            log.info("📂 Завантаження жанрів з: {}", resource.getPath());

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int count = 0;
                int errorCount = 0;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;

                    try {
                        int semicolonIdx = line.indexOf(';');
                        String code = null;
                        String name = null;

                        if (semicolonIdx >= 0) {
                            String leftPart = line.substring(0, semicolonIdx).trim();
                            name = line.substring(semicolonIdx + 1).trim();
                            if (name.isEmpty()) continue;

                            String[] tokens = leftPart.split("\\s+");
                            if (tokens.length > 0) {
                                code = tokens[tokens.length - 1];
                            } else {
                                code = leftPart;
                            }
                            if (code.isEmpty()) continue;
                        } else {
                            int firstSpace = line.indexOf(' ');
                            if (firstSpace < 0) {
                                log.warn("⚠️ Рядок без ';' та без пробілу: {}", line);
                                continue;
                            }
                            code = line.substring(0, firstSpace).trim();
                            name = line.substring(firstSpace + 1).trim();
                            if (code.isEmpty() || name.isEmpty()) continue;
                        }

                        genreMap.put(code, name);
                        String codeNoDots = code.replace(".", "");
                        if (!codeNoDots.equals(code)) {
                            genreMap.put(codeNoDots, name);
                        }
                        count++;
                    } catch (Exception e) {
                        errorCount++;
                        log.warn("⚠️ Помилка парсингу рядка: '{}', помилка: {}", line, e.getMessage());
                    }
                }
                log.info("✅ Завантажено {} жанрів з ресурсу, помилок: {}", count, errorCount);
            }
        } catch (Exception e) {
            log.error("❌ Критична помилка завантаження жанрів", e);
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
            log.info("✅ Додано жанри з БД: {}", rows.size());
        } catch (Exception e) {
            log.warn("⚠️ Не вдалося завантажити жанри з БД (можливо, таблиця порожня або відсутня)", e);
        }
    }

    @Override
    public String getGenreName(String code) {
        if (code == null) return "";
        String name = genreMap.get(code);
        if (name != null) return name;

        String codeNoDots = code.replace(".", "");
        name = genreMap.get(codeNoDots);
        if (name != null) {
            log.debug("Знайдено жанр за кодом без крапок: {} -> {}", code, name);
            return name;
        }
        log.warn("⚠️ Жанр з кодом '{}' не знайдено", code);
        return code;
    }

    @Override
    public List<String> getAllGenreNames() {
        return genreMap.values().stream().distinct().toList();
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
        String sql = "SELECT code, name, parent_code, fb2_code FROM genres";
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                GenreId id = GenreId.fromCode(rs.getString("code"));
                GenreId parentId = rs.getString("parent_code") != null
                        ? GenreId.fromCode(rs.getString("parent_code"))
                        : null;
                return new Genre(id, rs.getString("name"), parentId, rs.getString("fb2_code"));
            });
        } catch (Exception e) {
            log.warn("⚠️ Не вдалося завантажити жанри з БД для ієрархії, повертаємо порожній список", e);
            return List.of();
        }
    }
}