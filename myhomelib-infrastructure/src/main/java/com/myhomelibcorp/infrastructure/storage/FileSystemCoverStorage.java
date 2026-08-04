package com.myhomelibcorp.infrastructure.storage;

import com.myhomelibcorp.application.port.out.storage.CoverStorage;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class FileSystemCoverStorage implements CoverStorage {

    private final CollectionManager collectionManager;
    private final ConcurrentMap<String, Path> cache = new ConcurrentHashMap<>();

    private Path getBasePath() {
        String collectionId = collectionManager.getCurrentCollection() != null ?
                collectionManager.getCurrentCollection().getId() : "default";
        return Paths.get(System.getProperty("user.home"), ".myhomelibcorp", "covers", collectionId);
    }

    private String hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return input.replaceAll("[^a-zA-Z0-9]", "_");
        }
    }

    /**
     * Обчислює шлях до файлу обкладинки без перевірки існування.
     * Використовується для збереження нових обкладинок.
     */
    private Path resolveCoverPath(String bookId) {
        String hash = hash(bookId);
        return getBasePath().resolve(hash.substring(0, 2))
                .resolve(hash.substring(2, 4))
                .resolve(hash + ".jpg");
    }

    /**
     * Шукає існуючий файл обкладинки.
     * Використовується для читання.
     */
    private Optional<Path> findExistingCoverPath(String bookId) {
        Path path = cache.computeIfAbsent(bookId, this::resolveCoverPath);
        return Files.exists(path) ? Optional.of(path) : Optional.empty();
    }

    /**
     * @deprecated Використовуйте {@link #findExistingCoverPath(String)} для читання
     * та {@link #resolveCoverPath(String)} для обчислення шляху.
     */
    @Deprecated
    @Override
    public Optional<Path> getCoverPath(String bookId) {
        return findExistingCoverPath(bookId);
    }

    @Override
    public void save(String bookId, byte[] imageData, String mimeType) {
        if (imageData == null || imageData.length == 0) {
            log.warn("Cannot save empty cover for book: {}", bookId);
            return;
        }

        try {
            // Використовуємо resolveCoverPath замість getCoverPath
            Path target = resolveCoverPath(bookId);
            Files.createDirectories(target.getParent());

            Path temp = Files.createTempFile(target.getParent(), "cover_", ".tmp");
            Files.write(temp, imageData);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            cache.put(bookId, target);
            log.debug("Cover saved for book: {}", bookId);
        } catch (IOException e) {
            log.error("Failed to save cover for book: {}", bookId, e);
        }
    }

    @Override
    public Optional<byte[]> load(String bookId) {
        Optional<Path> pathOpt = findExistingCoverPath(bookId);
        if (pathOpt.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(pathOpt.get()));
        } catch (IOException e) {
            log.error("Failed to load cover for book: {}", bookId, e);
            return Optional.empty();
        }
    }

    @Override
    public void delete(String bookId) {
        findExistingCoverPath(bookId).ifPresent(path -> {
            try {
                Files.deleteIfExists(path);
                cache.remove(bookId);
            } catch (IOException e) {
                log.warn("Failed to delete cover for book: {}", bookId, e);
            }
        });
    }

    @Override
    public void deleteAll() {
        try {
            Path base = getBasePath();
            if (Files.exists(base)) {
                try (var stream = Files.walk(base)) {
                    stream.sorted((a, b) -> -a.compareTo(b))
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException e) {
                                    // ignore
                                }
                            });
                }
                cache.clear();
                log.info("All covers deleted for collection");
            }
        } catch (IOException e) {
            log.error("Failed to delete all covers", e);
        }
    }

    @Override
    public boolean exists(String bookId) {
        return findExistingCoverPath(bookId).isPresent();
    }

    @Override
    public long getTotalSize() {
        try {
            Path base = getBasePath();
            if (!Files.exists(base)) return 0;
            try (var stream = Files.walk(base)) {
                return stream.filter(Files::isRegularFile)
                        .mapToLong(p -> {
                            try {
                                return Files.size(p);
                            } catch (IOException e) {
                                return 0;
                            }
                        })
                        .sum();
            }
        } catch (IOException e) {
            return 0;
        }
    }
}