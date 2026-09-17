package com.myhomelibcorp.infrastructure.importer;

import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@Component
@Slf4j
public class DefaultImporterRegistry implements ImporterRegistry {

    private final List<BookImporterPort> importers;
    private final RuntimeExtensionRegistry runtimeExtensions;

    public DefaultImporterRegistry(List<BookImporterPort> importers, RuntimeExtensionRegistry runtimeExtensions) {
        this.importers = importers == null ? List.of() : List.copyOf(importers);
        this.runtimeExtensions = java.util.Objects.requireNonNull(runtimeExtensions, "runtimeExtensions");
    }

    private List<BookImporterPort> allImporters() {
        java.util.ArrayList<BookImporterPort> result = new java.util.ArrayList<>(importers);
        result.addAll(runtimeExtensions.bookImporters());
        return List.copyOf(result);
    }

    @Override
    public BookImporterPort findImporter(Path file) {
        log.debug("Пошук імпортера для файлу: {}", file.getFileName());
        for (BookImporterPort importer : allImporters()) {
            if (importer.supports(file)) {
                log.debug("Знайдено імпортер: {} для файлу: {}", importer.getFormatName(), file.getFileName());
                return importer;
            }
        }
        log.warn("Не знайдено імпортера для файлу: {}", file.getFileName());
        throw new IllegalArgumentException(
                "Непідтримуваний формат файлу: " + file.getFileName() +
                        ". Доступні формати: " + getSupportedFormats()
        );
    }

    @Override
    public List<BookImporterPort> getAllImporters() {
        return allImporters();
    }

    @Override
    public List<String> getSupportedFormats() {
        return allImporters().stream()
                .map(BookImporterPort::getFormatName)
                .toList();
    }
}