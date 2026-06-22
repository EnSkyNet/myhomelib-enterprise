package com.myhomelibcorp.infrastructure.service;

import com.myhomelibcorp.application.port.out.GenreService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class GenreServiceImpl implements GenreService {

    private final Map<String, String> genreMap = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        loadGenres();
        // Діагностика: вивести всі завантажені коди
        log.info("📊 Завантажено {} жанрів. Перші 10:", genreMap.size());
        genreMap.entrySet().stream().limit(10).forEach(e ->
                log.info("   '{}' -> '{}'", e.getKey(), e.getValue())
        );
    }

    private void loadGenres() {
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
                            // Рядок з ';' – ліва частина містить код (останнє слово) та назву справа
                            String leftPart = line.substring(0, semicolonIdx).trim();
                            name = line.substring(semicolonIdx + 1).trim();
                            if (name.isEmpty()) continue;

                            // Розбиваємо ліву частину на слова і беремо останнє
                            String[] tokens = leftPart.split("\\s+");
                            if (tokens.length > 0) {
                                code = tokens[tokens.length - 1];
                            } else {
                                code = leftPart;
                            }
                            if (code.isEmpty()) continue;
                        } else {
                            // Рядок без ';' – верхня категорія, наприклад "0.1 Фантастика"
                            int firstSpace = line.indexOf(' ');
                            if (firstSpace < 0) {
                                log.warn("⚠️ Рядок без ';' та без пробілу: {}", line);
                                continue;
                            }
                            code = line.substring(0, firstSpace).trim();
                            name = line.substring(firstSpace + 1).trim();
                            if (code.isEmpty() || name.isEmpty()) continue;
                        }

                        // Додаємо до мапи
                        genreMap.put(code, name);
                        // Додаємо варіант без крапок для сумісності
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
                log.info("✅ Завантажено {} жанрів, помилок: {}", count, errorCount);
            }
        } catch (Exception e) {
            log.error("❌ Критична помилка завантаження жанрів", e);
            // Не кидаємо виняток далі, щоб контекст піднявся
        }
    }

    @Override
    public String getGenreName(String code) {
        if (code == null) return "";
        // Спершу шукаємо точний збіг
        String name = genreMap.get(code);
        if (name != null) {
            return name;
        }
        // Якщо не знайдено, пробуємо без крапок
        String codeNoDots = code.replace(".", "");
        name = genreMap.get(codeNoDots);
        if (name != null) {
            log.debug("Знайдено жанр за кодом без крапок: {} -> {}", code, name);
            return name;
        }
        // Якщо код містить крапки, пробуємо додати крапки
        if (!code.contains(".") && code.length() >= 2) {
            // Спроба перетворити "0112" -> "0.1.12"
            // Це складно, тому просто логуємо
            log.warn("⚠️ Жанр з кодом '{}' не знайдено. Доступні коди (зразок): {}",
                    code, genreMap.keySet().stream().limit(5).toList());
        } else {
            log.warn("⚠️ Жанр з кодом '{}' не знайдено", code);
        }
        return code; // Повертаємо код, якщо назву не знайдено
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
}
