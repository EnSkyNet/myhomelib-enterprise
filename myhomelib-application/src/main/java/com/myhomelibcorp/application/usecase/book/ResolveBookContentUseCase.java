package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.shared.util.FileNameSupport;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Application boundary for resolving a catalogue book into one safe physical
 * document. UI never needs to call storage/archive output ports directly.
 */
@Component
@RequiredArgsConstructor
public class ResolveBookContentUseCase {
    public static final Set<String> READER_EXTENSIONS = Set.of("fb2", "fbd", "epub", "txt", "text", "md");
    public static final Set<String> DETAILS_EXTENSIONS = Set.of(
            "fb2", "fbd", "epub", "txt", "text", "md", "mobi", "azw", "azw3", "pdf", "djvu", "djv");

    private final BookResourcePort resourcePort;

    public ResolvedBookContent execute(BookDto book, Set<String> allowedExtensions) throws IOException {
        if (book == null) throw new IOException("Книга не задана");
        Path physical = resourcePort.locateBookFile(
                        book.getFileName(), book.getFolder(), book.getCollectionRoot(), book.getArchiveEntry())
                .orElseThrow(() -> new IOException("Локальний файл книги не знайдено"));

        if (!resourcePort.isArchive(physical.toString())) return new ResolvedBookContent(physical, false);

        String selected = trim(book.getArchiveEntry());
        if (selected.isBlank()) {
            selected = resourcePort.listArchiveEntries(physical).stream()
                    .filter(name -> allowed(allowedExtensions, name))
                    .findFirst().orElse("");
        }
        if (selected.isBlank() || !allowed(allowedExtensions, selected)) {
            throw new IOException("В архіві не знайдено підтримуваний файл книги");
        }

        Optional<InputStream> opened = resourcePort.readArchiveEntry(physical, selected);
        if (opened.isEmpty()) throw new IOException("Не вдалося прочитати запис архіву: " + selected);
        Path temp = Files.createTempFile("myhomelib-book-content-", suffix(selected));
        boolean success = false;
        try (InputStream in = opened.get(); OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > ArchiveSafetyLimits.MAX_ENTRY_BYTES) {
                    throw new IOException("Запис книги в архіві перевищує безпечний ліміт");
                }
                out.write(buffer, 0, read);
            }
            success = true;
            return new ResolvedBookContent(temp, true);
        } finally {
            if (!success) Files.deleteIfExists(temp);
        }
    }

    private static boolean allowed(Set<String> allowed, String name) {
        return allowed != null && allowed.contains(FileNameSupport.extension(name));
    }


    private static String suffix(String name) {
        String ext = FileNameSupport.extension(name);
        return ext.isBlank() ? ".book" : "." + ext;
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
}
