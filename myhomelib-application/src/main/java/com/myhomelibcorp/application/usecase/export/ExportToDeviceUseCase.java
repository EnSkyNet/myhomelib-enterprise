package com.myhomelibcorp.application.usecase.export;

import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExportToDeviceUseCase {

    private final BookQueryRepository bookQueryRepository;
    private final List<BookConverter> converters;

    private final Map<ExportRequest.ExportFormat, String> formatExtensions = Map.of(
            ExportRequest.ExportFormat.FB2, ".fb2",
            ExportRequest.ExportFormat.FB2_ZIP, ".fb2.zip",
            ExportRequest.ExportFormat.TXT, ".txt",
            ExportRequest.ExportFormat.PDF, ".pdf",
            ExportRequest.ExportFormat.EPUB, ".epub",
            ExportRequest.ExportFormat.MOBI, ".mobi",
            ExportRequest.ExportFormat.LRF, ".lrf"
    );

    public record ExportResult(int exported, int failed, List<String> errors) {
        public static ExportResult empty() {
            return new ExportResult(0, 0, new ArrayList<>());
        }
    }

    public ExportResult execute(ExportRequest request) {
        if (request.getBookIds().isEmpty()) {
            log.warn("Немає книг для експорту");
            return ExportResult.empty();
        }

        log.info("Початок експорту {} книг у формат {}", request.getBookIds().size(), request.getFormat());

        AtomicInteger exported = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        List<String> errors = new ArrayList<>();

        Path destination = request.getDestinationFolder();
        if (!Files.exists(destination)) {
            try {
                Files.createDirectories(destination);
            } catch (Exception e) {
                log.error("Не вдалося створити папку: {}", destination, e);
                return new ExportResult(0, request.getBookIds().size(), List.of("Не вдалося створити папку: " + e.getMessage()));
            }
        }

        // Знаходимо відповідний конвертер
        BookConverter converter = findConverter(request.getFormat());
        if (converter == null) {
            log.error("Конвертер для формату {} не знайдено", request.getFormat());
            return new ExportResult(0, request.getBookIds().size(), List.of("Конвертер не знайдено"));
        }

        for (BookId bookId : request.getBookIds()) {
            try {
                Book book = bookQueryRepository.findById(bookId)
                        .orElseThrow(() -> new IllegalArgumentException("Книгу не знайдено: " + bookId));

                log.debug("Експорт книги: {}", book.getTitle());

                // Формуємо ім'я файлу
                String fileName = generateFileName(book, request);
                Path targetFile = destination.resolve(fileName + converter.getTargetExtension());

                // Перевірка на існування
                if (Files.exists(targetFile) && !request.isOverwriteExisting()) {
                    // Додаємо номер до імені
                    int counter = 1;
                    Path newTarget;
                    do {
                        String newName = fileName + " (" + counter + ")";
                        newTarget = destination.resolve(newName + converter.getTargetExtension());
                        counter++;
                    } while (Files.exists(newTarget));
                    targetFile = newTarget;
                }

                // Отримуємо потік книги через BookQueryRepository або BookFile
                try (InputStream sourceStream = getBookStream(book)) {
                    converter.convert(book, sourceStream, targetFile);
                    exported.incrementAndGet();
                    log.info("Експортовано: {} -> {}", book.getTitle(), targetFile.getFileName());
                }

            } catch (Exception e) {
                failed.incrementAndGet();
                String error = String.format("Помилка експорту книги %s: %s", bookId, e.getMessage());
                errors.add(error);
                log.error(error, e);
            }
        }

        log.info("Експорт завершено: експортовано {}, помилок: {}", exported.get(), failed.get());
        return new ExportResult(exported.get(), failed.get(), errors);
    }

    /**
     * Отримує InputStream для книги.
     * Якщо книга є архівом – отримуємо перший FB2 з архіву.
     */
    private InputStream getBookStream(Book book) throws Exception {
        // Перевіряємо, чи книга є архівом
        String fileName = book.getFileName();
        String archiveEntry = book.getArchiveEntry();
        String folder = book.getFolder();
        String collectionRoot = book.getCollectionRoot();

        Path bookPath = buildFilePath(collectionRoot, folder, fileName);

        if (!Files.exists(bookPath)) {
            throw new IllegalArgumentException("Файл не знайдено: " + bookPath);
        }

        // Якщо це архів (zip, fb2zip) – шукаємо всередині
        if (isArchive(fileName)) {
            return readFromArchive(bookPath, archiveEntry);
        }

        // Звичайний файл
        return Files.newInputStream(bookPath);
    }

    private Path buildFilePath(String root, String folder, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("Ім'я файлу не може бути порожнім");
        }

        Path filePath = Path.of(fileName);
        if (filePath.isAbsolute()) {
            return filePath;
        }

        if (folder != null && !folder.isBlank()) {
            Path folderPath = Path.of(folder);
            if (folderPath.isAbsolute()) {
                return folderPath.resolve(fileName);
            }
            if (root != null && !root.isBlank()) {
                return Path.of(root).resolve(folderPath).resolve(fileName);
            }
            return folderPath.resolve(fileName);
        }

        if (root != null && !root.isBlank()) {
            return Path.of(root).resolve(fileName);
        }

        return Path.of(fileName);
    }

    private boolean isArchive(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".zip") || lower.endsWith(".fb2zip") || lower.endsWith(".fbd");
    }

    private InputStream readFromArchive(Path archivePath, String entryName) throws Exception {
        if (entryName == null || entryName.isBlank()) {
            // Шукаємо перший файл з розширенням .fb2 або .fbd
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archivePath.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    String name = entry.getName().toLowerCase();
                    if (name.endsWith(".fb2") || name.endsWith(".fbd")) {
                        return zip.getInputStream(entry);
                    }
                }
                throw new IllegalArgumentException("В архіві не знайдено FB2 файлів");
            }
        }

        // Читаємо конкретний запис
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archivePath.toFile())) {
            var entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IllegalArgumentException("Запис не знайдено в архіві: " + entryName);
            }
            return zip.getInputStream(entry);
        }
    }

    private BookConverter findConverter(ExportRequest.ExportFormat format) {
        String targetExt = formatExtensions.get(format);
        if (targetExt == null) {
            log.warn("Невідомий формат: {}", format);
            return null;
        }

        // Шукаємо конвертер за розширенням
        for (BookConverter converter : converters) {
            if (converter.getTargetExtension().equals(targetExt)) {
                return converter;
            }
        }

        // Якщо не знайдено – шукаємо за форматом
        for (BookConverter converter : converters) {
            if (converter.getFormatName().equalsIgnoreCase(format.name())) {
                return converter;
            }
        }

        return null;
    }

    private String generateFileName(Book book, ExportRequest request) {
        if (request.getCustomFileNameTemplate() != null && !request.getCustomFileNameTemplate().isEmpty()) {
            String template = request.getCustomFileNameTemplate();
            template = template.replace("%t", sanitizeFileName(book.getTitle()));
            template = template.replace("%a", sanitizeFileName(book.authorsText()));
            template = template.replace("%s", book.getSeries() != null ? sanitizeFileName(book.getSeries()) : "");
            template = template.replace("%n", book.getSequenceNumber() != null ? String.valueOf(book.getSequenceNumber()) : "");
            template = template.replace("%id", book.getId().asString());
            return template;
        }

        // Стандартне ім'я: Автор - Назва
        String author = book.getAuthors().isEmpty() ? "Невідомий" : book.getAuthors().get(0).getFullName();
        String title = book.getTitle();
        return sanitizeFileName(author + " - " + title);
    }

    private String sanitizeFileName(String name) {
        if (name == null) return "unknown";
        return name.replaceAll("[<>:\"/\\|?*]", "_")
                .replaceAll("\\s+", " ")
                .trim();
    }
}