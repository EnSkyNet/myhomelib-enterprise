package com.myhomelibcorp.infrastructure.importer.txt;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.infrastructure.importer.AbstractBookImporter;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Component
public class TxtImporter extends AbstractBookImporter {
    @Override
    public boolean supports(Path file) {
        return file != null && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt");
    }

    @Override public String getFormatName() { return "TXT"; }

    @Override
    protected Book parseBook(Path file) throws Exception {
        String title = stripExtension(file.getFileName().toString());
        String firstLine = firstMeaningfulLine(file, StandardCharsets.UTF_8);
        if (firstLine == null) firstLine = firstMeaningfulLine(file, Charset.forName("Windows-1251"));
        if (firstLine != null && firstLine.length() <= 180 && !looksLikeBinary(firstLine)) title = firstLine;

        BookMetadata metadata = BookMetadata.builder()
                .annotation("")
                .keywords("")
                .language(LanguageCode.of("und"))
                .rate(0)
                .progress(0)
                .build();
        BookFile bookFile = new BookFile(file.getFileName().toString(),
                file.getParent() != null ? file.getParent().toString() : "", "", Files.size(file), null);
        return createBook(title, List.of(new Author("", "", "Невідомий автор")), List.of(),
                "", 0, metadata, bookFile, LocalDateTime.now());
    }

    private String firstMeaningfulLine(Path file, Charset charset) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(file, charset)) {
            for (int i = 0; i < 20; i++) {
                String line = r.readLine();
                if (line == null) return null;
                line = line.strip();
                if (!line.isEmpty()) return line;
            }
            return null;
        } catch (CharacterCodingException invalidEncoding) {
            return null;
        }
    }

    private boolean looksLikeBinary(String s) {
        int controls = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\t') controls++;
        }
        return controls > 1;
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
