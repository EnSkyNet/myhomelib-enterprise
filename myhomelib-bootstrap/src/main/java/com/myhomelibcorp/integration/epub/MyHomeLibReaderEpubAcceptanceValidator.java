package com.myhomelibcorp.integration.epub;

import com.myhomelibcorp.epub.optimizer.acceptance.EpubAcceptanceValidator;
import com.myhomelibcorp.reader.api.FileBookSource;
import com.myhomelibcorp.reader.api.ParseOptions;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.format.epub.EpubParser;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public final class MyHomeLibReaderEpubAcceptanceValidator implements EpubAcceptanceValidator {
    @Override public Kind kind() { return Kind.READER_REOPEN; }
    @Override public String id() { return "myhomelib-reader"; }

    @Override
    public Result validate(Path epub) {
        ReaderDocument document = null;
        try {
            document = new EpubParser().parse(new FileBookSource(epub), ParseOptions.minimal());
            if (document == null || document.chapters() == null || document.chapters().isEmpty() || document.totalTextLength() <= 0) {
                return Result.failed("Reader opened the EPUB but found no readable spine text");
            }
            return Result.passed("chapters=" + document.chapters().size() + ", textLength=" + document.totalTextLength());
        } catch (Exception failure) {
            return Result.failed(failure.getClass().getSimpleName() + ": " + safe(failure.getMessage()));
        } finally {
            if (document != null && document.resources() instanceof AutoCloseable closeable) {
                try { closeable.close(); } catch (Exception ignored) { }
            }
        }
    }

    private String safe(String value) { return value == null ? "reader reopen failed" : value; }
}
