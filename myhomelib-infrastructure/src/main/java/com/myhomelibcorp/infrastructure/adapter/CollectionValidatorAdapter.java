package com.myhomelibcorp.infrastructure.adapter;

import com.myhomelibcorp.application.dto.CreateCollectionRequest;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.port.out.validation.CollectionValidatorPort;
import com.myhomelibcorp.application.util.SafeFilePublication;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
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
            Path path = Paths.get(dbPath).toAbsolutePath().normalize();
            Path parent = path.getParent();
            if (parent == null) return false;
            SafeFilePublication.requireSafeDirectoryPath(parent, "collection database parent");
            if (!Files.isWritable(parent)) return false;
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                SafeFilePublication.requireSafeRegularFile(path, "collection database");
                return Files.isWritable(path);
            }
            return true;
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
            Path root = request.getRootFolder().toAbsolutePath().normalize();
            try {
                SafeFilePublication.requireSafeDirectoryPath(root, "collection root");
                if (!Files.isWritable(root)) {
                    errors.add("Немає прав на запис у кореневу папку: " + root);
                }
            } catch (Exception unsafeRoot) {
                errors.add("Коренева папка не існує або небезпечна: " + root);
            }
        }

        // Перевірка джерела (якщо вказано)
        if (request.getSourcePath() != null && !request.getSourcePath().isBlank()) {
            Path source = Paths.get(request.getSourcePath()).toAbsolutePath().normalize();
            try {
                SafeFilePublication.requireSafeRegularFile(source, "collection source");
            } catch (Exception unsafeSource) {
                errors.add("Джерело не існує або небезпечне: " + request.getSourcePath());
            }
        }

        return errors;
    }
}