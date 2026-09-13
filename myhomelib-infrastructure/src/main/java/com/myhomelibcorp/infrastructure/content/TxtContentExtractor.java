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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Streaming plain-text extractor with shared UTF/legacy encoding detection. */
@Component
public final class TxtContentExtractor implements ContentExtractor {
    private static final int PROGRESS_LINE_INTERVAL = 512;

    @Override public String id() { return "txt-content"; }
    @Override public boolean supports(String format) { return "txt".equals(normalizeFormat(format)); }

    @Override
    public ExtractedContent extract(ContentExtractionRequest request, ContentExtractionContext context) throws IOException {
        ContentExtractionContext ctx = context == null ? ContentExtractionContext.none() : context;
        ContentDocumentBuilder document = new ContentDocumentBuilder(request.source().id(), "txt");
        List<ContentDocumentBuilder.ParagraphBlock> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        int lines = 0;
        int paragraphNumber = 0;
        try (InputStream input = request.source().openStream();
             BufferedReader reader = TextStreamDecoder.open(input, null)) {
            String line;
            while ((line = reader.readLine()) != null) {
                ctx.throwIfCancelled();
                lines++;
                String normalizedLine = line.strip();
                if (normalizedLine.isBlank()) {
                    if (!paragraph.isEmpty()) {
                        blocks.add(new ContentDocumentBuilder.ParagraphBlock(
                                "p" + (++paragraphNumber), paragraph.toString()));
                        paragraph.setLength(0);
                    }
                } else {
                    if (!paragraph.isEmpty()) paragraph.append(' ');
                    paragraph.append(normalizedLine);
                }
                if (lines % PROGRESS_LINE_INTERVAL == 0) ctx.report("txt-text", lines, 0L);
            }
        }
        if (!paragraph.isEmpty()) {
            blocks.add(new ContentDocumentBuilder.ParagraphBlock("p" + (++paragraphNumber), paragraph.toString()));
        }
        if (!blocks.isEmpty()) document.addChapter("txt-chapter-1", "", blocks);
        return document.build();
    }

    private static String normalizeFormat(String format) {
        return format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
    }
}
