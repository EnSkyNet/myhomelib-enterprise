package com.myhomelibcorp.infrastructure.content;

import com.myhomelibcorp.application.content.ContentExtractionContext;
import com.myhomelibcorp.application.content.ContentExtractionRequest;
import com.myhomelibcorp.application.content.ExtractedContent;
import com.myhomelibcorp.application.port.out.content.ContentExtractor;
import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Secure streaming FB2 full-text extractor. */
@Component
public final class Fb2ContentExtractor implements ContentExtractor {
    private static final int PROGRESS_BLOCK_INTERVAL = 128;
    private static final java.util.Set<String> TEXT_BLOCKS = java.util.Set.of(
            "p", "v", "subtitle", "text-author", "date", "th");
    // These extractors are singleton beans and may be invoked concurrently by indexing workers.
    // XMLInputFactory implementations are not guaranteed to be thread-safe.
    private final ThreadLocal<XMLInputFactory> xmlFactory =
            ThreadLocal.withInitial(() -> SecureXmlInputFactory.create(false, false));
    private final int maxTextChars;

    public Fb2ContentExtractor() { this(ContentDocumentBuilder.MAX_TEXT_CHARS); }
    Fb2ContentExtractor(int maxTextChars) { this.maxTextChars = maxTextChars; }

    @Override public String id() { return "fb2-content"; }
    @Override public boolean supports(String format) { return "fb2".equals(normalizeFormat(format)); }

    @Override
    public ExtractedContent extract(ContentExtractionRequest request, ContentExtractionContext context) throws IOException {
        ContentExtractionContext ctx = context == null ? ContentExtractionContext.none() : context;
        ContentDocumentBuilder document = new ContentDocumentBuilder(request.source().id(), "fb2",
                maxTextChars, ContentDocumentBuilder.MAX_PARAGRAPHS);
        try (InputStream input = request.source().openStream()) {
            XMLStreamReader reader = xmlFactory.get().createXMLStreamReader(input);
            try {
                parse(reader, document, ctx);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException malformed) {
            throw new IOException("Malformed FB2 XML", malformed);
        }
        return document.build();
    }

    private static void parse(XMLStreamReader reader, ContentDocumentBuilder document, ContentExtractionContext context)
            throws XMLStreamException, IOException {
        int bodyDepth = 0;
        int sectionDepth = 0;
        int chapterCounter = 0;
        int paragraphCounter = 0;
        int titleDepth = 0;
        String chapterId = null;
        String chapterTitle = "";
        String activeBlock = null;
        NormalizedTextBuffer blockText = null;

        while (reader.hasNext()) {
            context.throwIfCancelled();
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String local = reader.getLocalName().toLowerCase(Locale.ROOT);
                if ("body".equals(local)) {
                    bodyDepth++;
                    continue;
                }
                if (bodyDepth <= 0) continue;
                if ("section".equals(local)) {
                    sectionDepth++;
                    if (sectionDepth == 1) {
                        document.finishChapter(chapterTitle);
                        chapterId = "fb2-chapter-" + (++chapterCounter);
                        chapterTitle = "";
                    }
                    continue;
                }
                if ("title".equals(local)) {
                    titleDepth++;
                    continue;
                }
                if (TEXT_BLOCKS.contains(local) && activeBlock == null) {
                    activeBlock = local;
                    blockText = document.paragraphBuffer();
                }
            } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                    && bodyDepth > 0 && activeBlock != null && blockText != null) {
                blockText.append(reader.getTextCharacters(), reader.getTextStart(), reader.getTextLength());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String local = reader.getLocalName().toLowerCase(Locale.ROOT);
                if (bodyDepth > 0 && activeBlock != null && activeBlock.equals(local) && blockText != null) {
                    String normalized = blockText.text();
                    if (!normalized.isBlank()) {
                        if (chapterId == null) chapterId = "fb2-chapter-" + (++chapterCounter);
                        String paragraphId = "p" + (++paragraphCounter);
                        document.addParagraph(chapterId, paragraphId, normalized);
                        if (titleDepth > 0 && chapterTitle.isBlank()) chapterTitle = normalized;
                        if (paragraphCounter % PROGRESS_BLOCK_INTERVAL == 0) {
                            context.report("fb2-text", paragraphCounter, 0L);
                        }
                    }
                    activeBlock = null;
                    blockText = null;
                }
                if ("title".equals(local) && bodyDepth > 0 && titleDepth > 0) {
                    titleDepth--;
                } else if ("section".equals(local) && bodyDepth > 0) {
                    if (sectionDepth == 1 && document.hasPendingChapter()) {
                        document.finishChapter(chapterTitle);
                        chapterId = null;
                        chapterTitle = "";
                    }
                    sectionDepth = Math.max(0, sectionDepth - 1);
                } else if ("body".equals(local) && bodyDepth > 0) {
                    if (document.hasPendingChapter()) {
                        document.finishChapter(chapterTitle);
                        chapterId = null;
                        chapterTitle = "";
                    }
                    bodyDepth--;
                    sectionDepth = 0;
                    titleDepth = 0;
                }
            }
        }
        document.finishChapter(chapterTitle);
    }

    private static String normalizeFormat(String format) {
        return format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
    }
}
