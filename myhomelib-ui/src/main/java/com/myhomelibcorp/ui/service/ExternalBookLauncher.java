package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.usecase.book.ExternalReaderMaterializationCache;
import com.myhomelibcorp.application.util.CommandTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
public class ExternalBookLauncher {
    private final ApplicationSettingsPort settings;
    private final BookResourcePort resources;
    private final ExternalReaderMaterializationCache materializationCache;

    public ExternalBookLauncher(ApplicationSettingsPort settings,
                                BookResourcePort resources,
                                ExternalReaderMaterializationCache materializationCache) {
        this.settings = settings;
        this.resources = resources;
        this.materializationCache = materializationCache;
    }

    public void open(BookDto dto) throws Exception {
        if (dto == null) throw new IllegalArgumentException("Книгу не вибрано");
        String source = nonBlank(dto.getArchiveEntry(), dto.getFileName());
        String ext = extension(source);
        MaterializedExternalBook materialized = materialize(dto, ext);
        ExternalReaderMaterializationCache.Lease lease = materialized.lease();
        Path file = materialized.path();
        try {
            String configured = settings.get("reader.external." + ext, "");
            String command = configured == null ? "" : configured.trim();
            if (command.isEmpty()) {
                if (!Desktop.isDesktopSupported()) throw new IllegalStateException("Desktop API недоступний");
                Desktop.getDesktop().open(file.toFile());
                if (lease != null) lease.keepUntilNextStartup();
                return;
            }
            List<String> args = new java.util.ArrayList<>(CommandTemplate.expand(command, Map.of(
                    "%FILE%", file.toAbsolutePath().toString(),
                    "%TITLE%", safe(dto.getTitle()),
                    "%AUTHOR%", safe(dto.getAuthorsText()))));
            if (args.stream().noneMatch(a -> a.equals(file.toAbsolutePath().toString()))) {
                args.add(file.toAbsolutePath().toString());
            }
            Process process = new ProcessBuilder(args).start();
            if (lease != null) lease.retainUntil(process);
        } finally {
            if (lease != null) lease.close();
        }
    }

    private MaterializedExternalBook materialize(BookDto dto, String ext) throws Exception {
        if (dto.getArchiveEntry() == null || dto.getArchiveEntry().isBlank()) {
            Path located = resources.locateBookFile(
                    dto.getFileName(), dto.getFolder(), dto.getCollectionRoot(), dto.getArchiveEntry()).orElse(null);
            if (located != null && Files.isRegularFile(located) && !resources.isArchive(located.toString())) {
                return new MaterializedExternalBook(located.toAbsolutePath().normalize(), null);
            }
        }
        try (InputStream in = resources.readBookData(
                        dto.getFileName(), dto.getFolder(), dto.getCollectionRoot(), dto.getArchiveEntry())
                .orElseThrow(() -> new IllegalStateException("Дані книги недоступні"))) {
            ExternalReaderMaterializationCache.Lease lease = materializationCache.materialize(in, ext);
            return new MaterializedExternalBook(lease.path(), lease);
        }
    }

    private static String extension(String s) {
        int i = s == null ? -1 : s.lastIndexOf('.');
        return i < 0 ? "" : s.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static String nonBlank(String a, String b) { return a != null && !a.isBlank() ? a : (b == null ? "" : b); }
    private static String safe(String s) { return s == null ? "" : s; }

    private record MaterializedExternalBook(Path path, ExternalReaderMaterializationCache.Lease lease) { }
}
