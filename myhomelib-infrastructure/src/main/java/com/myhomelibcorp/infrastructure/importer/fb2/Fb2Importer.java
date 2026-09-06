package com.myhomelibcorp.infrastructure.importer.fb2;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.infrastructure.importer.AbstractBookImporter;
import com.myhomelibcorp.infrastructure.parser.fb2.Fb2Parser;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Locale;

@Component
@Slf4j
public class Fb2Importer extends AbstractBookImporter {

    private final Fb2Parser fb2Parser = new Fb2Parser();

    @Override
    public boolean supports(Path file) {
        return SupportedFormatRegistry.standard().isFormat(file, "fb2");
    }

    @Override
    public String getFormatName() {
        return "FB2";
    }

    @Override
    protected Book parseBook(Path file) throws Exception {
        log.debug("Парсинг FB2 файлу: {}", file);

        long fileSize = Files.size(file);
        String fileName = file.getFileName().toString();
        String folder = file.getParent() != null ? file.getParent().toString() : "";

        // ОДИН прохід по файлу
        Fb2Parser.ParseResult result;
        try (var is = Files.newInputStream(file)) {
            result = fb2Parser.parse(is);
        }

        BookMetadata metadata = BookMetadata.builder()
                .annotation(result.getAnnotation())
                .keywords("")
                .language(LanguageCode.of(result.getLanguage()))
                .rate(0)
                .progress(0)
                .build();

        BookFile bookFile = new BookFile(
                fileName,
                folder,
                "",
                fileSize,
                null
        );

        return createBook(
                result.getTitle(),
                result.getAuthors(),
                result.getGenres(),
                result.getSeries(),
                result.getSequenceNumber(),
                metadata,
                bookFile,
                LocalDateTime.now()
        );
    }
}
