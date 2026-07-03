package com.myhomelibcorp.infrastructure.importer;

import com.myhomelibcorp.application.imports.detector.BookFormatDetector;
import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultImporterRegistry implements ImporterRegistry {

    private final List<BookImporterPort> importers;
    private final BookFormatDetector formatDetector;

    @Override
    public BookImporterPort findImporter(Path file) {
        log.debug("Пошук імпортера для файлу: {}", file.getFileName());
        return importers.stream()
                .filter(importer -> importer.supports(file))
                .findFirst()
                .orElseThrow(() -> {
                    String formats = importers.stream()
                            .map(BookImporterPort::getFormatName)
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("немає");
                    throw new IllegalArgumentException(
                            "Непідтримуваний формат файлу: " + file.getFileName() +
                                    ". Доступні формати: " + formats
                    );
                });
    }

    @Override
    public List<BookImporterPort> getAllImporters() {
        return importers;
    }

    @Override
    public List<String> getSupportedFormats() {
        return importers.stream()
                .map(BookImporterPort::getFormatName)
                .toList();
    }
}