package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.util.CommandTemplate;
import com.myhomelibcorp.shared.util.AppPaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@Service
@Slf4j
public class ExternalBookLauncher {
    private final ApplicationSettingsPort settings;
    private final BookResourcePort resources;

    public ExternalBookLauncher(ApplicationSettingsPort settings, BookResourcePort resources) {
        this.settings = settings; this.resources = resources;
    }

    public void open(BookDto dto) throws Exception {
        if (dto == null) throw new IllegalArgumentException("Книгу не вибрано");
        String source = nonBlank(dto.getArchiveEntry(), dto.getFileName());
        String ext = extension(source);
        Path file = materialize(dto, ext);
        String configured = settings.get("reader.external." + ext, "");
        String command = configured == null ? "" : configured.trim();
        if (command.isEmpty()) {
            if (!Desktop.isDesktopSupported()) throw new IllegalStateException("Desktop API недоступний");
            Desktop.getDesktop().open(file.toFile());
            return;
        }
        List<String> args = new java.util.ArrayList<>(CommandTemplate.expand(command, Map.of(
                "%FILE%", file.toAbsolutePath().toString(),
                "%TITLE%", safe(dto.getTitle()),
                "%AUTHOR%", safe(dto.getAuthorsText()))));
        if (args.stream().noneMatch(a -> a.equals(file.toAbsolutePath().toString()))) args.add(file.toAbsolutePath().toString());
        new ProcessBuilder(args).start();
    }

    private Path materialize(BookDto dto, String ext) throws Exception {
        if (dto.getArchiveEntry() == null || dto.getArchiveEntry().isBlank()) {
            Path located = resources.locateBookFile(dto.getFileName(), dto.getFolder(), dto.getCollectionRoot(), dto.getArchiveEntry()).orElse(null);
            if (located != null && Files.isRegularFile(located) && !resources.isArchive(located.toString())) return located;
        }
        Path dir = AppPaths.cacheDir().resolve("external-reader");
        Files.createDirectories(dir);
        Path target = Files.createTempFile(dir, "book-", ext.isBlank() ? ".book" : "." + ext);
        try (InputStream in = resources.readBookData(dto.getFileName(), dto.getFolder(), dto.getCollectionRoot(), dto.getArchiveEntry())
                .orElseThrow(() -> new IllegalStateException("Дані книги недоступні"))) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        target.toFile().deleteOnExit();
        return target;
    }

    private static String extension(String s) { int i=s==null?-1:s.lastIndexOf('.'); return i<0?"":s.substring(i+1).toLowerCase(Locale.ROOT); }
    private static String nonBlank(String a,String b){ return a!=null&&!a.isBlank()?a:(b==null?"":b); }
    private static String safe(String s){ return s==null?"":s; }

}
