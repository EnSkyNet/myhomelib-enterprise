package com.myhomelibcorp.infrastructure.adapter;

import com.myhomelibcorp.application.dto.CreateCollectionRequest;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.port.out.validation.CollectionValidatorPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class CollectionValidatorAdapter implements CollectionValidatorPort {

    private final CollectionRepository collectionRepository;

    @Override
    public boolean existsByName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return collectionRepository.findByName(name).isPresent();
    }

    @Override
    public boolean isDbPathAvailable(String dbPath) {
        if (dbPath == null || dbPath.isBlank()) {
            return false;
        }
        try {
            Path path = Paths.get(dbPath);
            if (Files.exists(path)) {
                return Files.isWritable(path);
            }
            // Перевіряємо, чи можна створити файл у директорії
            Path parent = path.getParent();
            if (parent == null || !Files.exists(parent)) {
                return false;
            }
            return Files.isWritable(parent);
        } catch (Exception e) {
            log.warn("Помилка перевірки шляху до БД: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<String> validate(CreateCollectionRequest request) {
        List<String> errors = new ArrayList<>();

        if (request == null) {
            errors.add("Запит не може бути порожнім");
            return errors;
        }

        // Перевірка назви
        if (request.getName() == null || request.getName().isBlank()) {
            errors.add("Назва колекції не може бути порожньою");
        } else if (existsByName(request.getName())) {
            errors.add("Колекція з назвою '" + request.getName() + "' вже існує");
        }

        // Перевірка шляху до БД
        if (request.getDbFile() != null) {
            String dbPath = request.getDbFile().toString();
            if (!isDbPathAvailable(dbPath)) {
                errors.add("Шлях до БД недоступний: " + dbPath);
            }
        }

        // Перевірка кореневої папки
        if (request.getRootFolder() != null) {
            Path root = request.getRootFolder();
            if (!Files.exists(root)) {
                errors.add("Коренева папка не існує: " + root);
            } else if (!Files.isDirectory(root)) {
                errors.add("Шлях не є директорією: " + root);
            } else if (!Files.isWritable(root)) {
                errors.add("Немає прав на запис у кореневу папку: " + root);
            }
        }

        // Перевірка джерела (якщо вказано)
        if (request.getSourcePath() != null && !request.getSourcePath().isBlank()) {
            Path source = Paths.get(request.getSourcePath());
            if (!Files.exists(source)) {
                errors.add("Джерело не існує: " + request.getSourcePath());
            }
        }

        return errors;
    }
}