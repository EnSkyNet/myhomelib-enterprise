package com.myhomelibcorp.infrastructure.importer.comic;

import com.myhomelibcorp.application.port.out.cover.ArchiveReader;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.infrastructure.importer.AbstractBookImporter;
import com.myhomelibcorp.shared.comic.ComicPageNameSupport;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.List;

/** Imports CBZ/CBR as one comic document; contained images are pages, not separate books. */
@Component
@Order(100)
@RequiredArgsConstructor
public class ComicArchiveImporter extends AbstractBookImporter {
    private static final SupportedFormatRegistry FORMATS = SupportedFormatRegistry.standard();

    private final ArchiveReader archiveReader;

    @Override
    public boolean supports(Path file) {
        return FORMATS.isFormat(file, "cbz", "cbr");
    }

    @Override
    public String getFormatName() {
        return "COMIC (CBZ/CBR)";
    }

    @Override
    protected Book parseBook(Path file) throws Exception {
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Comic archive does not exist: " + file);
        }
        List<String> pages = ComicPageNameSupport.sortPages(archiveReader.listEntries(file));
        if (pages.isEmpty()) {
            throw new IOException("Comic archive contains no supported image pages");
        }

        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String title = dot > 0 ? name.substring(0, dot) : name;
        Path parent = file.toAbsolutePath().normalize().getParent();
        BookMetadata metadata = BookMetadata.builder()
                .annotation("")
                .keywords("")
                .language(LanguageCode.of("und"))
                .rate(0)
                .progress(0)
                .build();
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("Comic archive is not a regular file: " + file);
        BookFile bookFile = new BookFile(
                name,
                parent == null ? "" : parent.toString(),
                "",
                attributes.size(),
                null);
        LocalDateTime modified = fileUpdateTime(file);
        return createBook(title, List.of(), List.of(), "", 0,
                metadata, bookFile, modified);
    }

    @Override
    public long countBooks(Path file) {
        if (!supports(file) || file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return 0;
        try {
            return ComicPageNameSupport.sortPages(archiveReader.listEntries(file)).isEmpty() ? 0 : 1;
        } catch (RuntimeException error) {
            return -1;
        }
    }
}
