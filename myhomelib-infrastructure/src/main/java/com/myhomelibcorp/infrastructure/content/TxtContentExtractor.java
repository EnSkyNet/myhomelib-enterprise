package com.myhomelibcorp.infrastructure.content;

import com.myhomelibcorp.application.content.ContentExtractionContext;
import com.myhomelibcorp.application.content.ContentExtractionRequest;
import com.myhomelibcorp.application.content.ExtractedContent;
import com.myhomelibcorp.application.port.out.content.ContentExtractor;
import com.myhomelibcorp.shared.text.TextStreamDecoder;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Streaming plain-text extractor with shared UTF/legacy encoding detection. */
@Component
public final class TxtContentExtractor implements ContentExtractor {
    private static final int PROGRESS_LINE_INTERVAL = 512;
    private final int maxTextChars;

    public TxtContentExtractor() { this(ContentDocumentBuilder.MAX_TEXT_CHARS); }
    TxtContentExtractor(int maxTextChars) { this.maxTextChars = maxTextChars; }

    @Override public String id() { return "txt-content"; }
    @Override public boolean supports(String format) { return "txt".equals(normalizeFormat(format)); }

    @Override
    public ExtractedContent extract(ContentExtractionRequest request, ContentExtractionContext context) throws IOException {
        ContentExtractionContext ctx = context == null ? ContentExtractionContext.none() : context;
        ContentDocumentBuilder document = new ContentDocumentBuilder(request.source().id(), "txt",
                maxTextChars, ContentDocumentBuilder.MAX_PARAGRAPHS);
        NormalizedTextBuffer paragraph = document.paragraphBuffer();
        long lines = 0;
        int paragraphNumber = 0;
        boolean lineHasText = false;
        boolean skipLf = false;
        try (InputStream input = request.source().openStream();
             BufferedReader reader = TextStreamDecoder.open(input, null)) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                ctx.throwIfCancelled();
                for (int i = 0; i < read; i++) {
                    char c = buffer[i];
                    if (skipLf && c == '\n') {
                        skipLf = false;
                        continue;
                    }
                    skipLf = c == '\r';
                    if (c == '\r' || c == '\n') {
                        lines++;
                        if (!lineHasText && !paragraph.isEmpty()) {
                            document.addParagraph("txt-chapter-1", "p" + (++paragraphNumber), paragraph.text());
                            paragraph = document.paragraphBuffer();
                        } else {
                            paragraph.separate();
                        }
                        lineHasText = false;
                        if (lines % PROGRESS_LINE_INTERVAL == 0) ctx.report("txt-text", lines, 0L);
                    } else {
                        if (!Character.isWhitespace(c)) lineHasText = true;
                        paragraph.append(c);
                    }
                }
            }
        }
        if (!paragraph.isEmpty()) document.addParagraph("txt-chapter-1", "p" + (++paragraphNumber), paragraph.text());
        document.finishChapter("");
        return document.build();
    }

    private static String normalizeFormat(String format) {
        return format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
    }
}
