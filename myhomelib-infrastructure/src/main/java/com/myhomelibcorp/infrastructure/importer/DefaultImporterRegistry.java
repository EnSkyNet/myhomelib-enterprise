package com.myhomelibcorp.infrastructure.importer;

import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Registry for built-in and runtime-provided book importers.
 *
 * <p>Built-in importers are resolved lazily on first registry use. Injecting a
 * {@code List<BookImporterPort>} here would force Spring to instantiate every
 * importer and its dependency graph during desktop startup even when the user
 * never imports a file.</p>
 */
@Component
@Slf4j
public class DefaultImporterRegistry implements ImporterRegistry {

    private final ObjectProvider<BookImporterPort> coreImporterProvider;
    private final RuntimeExtensionRegistry runtimeExtensions;
    private volatile List<BookImporterPort> resolvedCoreImporters;

    public DefaultImporterRegistry(
            ObjectProvider<BookImporterPort> coreImporterProvider,
            RuntimeExtensionRegistry runtimeExtensions) {
        this.coreImporterProvider = Objects.requireNonNull(coreImporterProvider, "coreImporterProvider");
        this.runtimeExtensions = Objects.requireNonNull(runtimeExtensions, "runtimeExtensions");
    }

    private List<BookImporterPort> coreImporters() {
        List<BookImporterPort> current = resolvedCoreImporters;
        if (current != null) return current;

        synchronized (this) {
            current = resolvedCoreImporters;
            if (current == null) {
                current = coreImporterProvider.orderedStream().toList();
                resolvedCoreImporters = current;
                log.debug("Resolved {} built-in book importer(s) on first use", current.size());
            }
        }
        return current;
    }

    private List<BookImporterPort> allImporters() {
        List<BookImporterPort> pluginImporters = runtimeExtensions.bookImporters();
        if (pluginImporters.isEmpty()) return coreImporters();

        ArrayList<BookImporterPort> result = new ArrayList<>(coreImporters().size() + pluginImporters.size());
        result.addAll(coreImporters());
        result.addAll(pluginImporters);
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
