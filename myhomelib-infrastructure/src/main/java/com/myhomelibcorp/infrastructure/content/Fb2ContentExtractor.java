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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Secure streaming FB2 full-text extractor. */
@Component
public final class Fb2ContentExtractor implements ContentExtractor {
    private static final int PROGRESS_BLOCK_INTERVAL = 128;
    private static final java.util.Set<String> TEXT_BLOCKS = java.util.Set.of(
            "p", "v", "subtitle", "text-author", "date", "th");
    private final XMLInputFactory xmlFactory = SecureXmlInputFactory.create(false, false);

    @Override public String id() { return "fb2-content"; }
    @Override public boolean supports(String format) { return "fb2".equals(normalizeFormat(format)); }

    @Override
    public ExtractedContent extract(ContentExtractionRequest request, ContentExtractionContext context) throws IOException {
        ContentExtractionContext ctx = context == null ? ContentExtractionContext.none() : context;
        ContentDocumentBuilder document = new ContentDocumentBuilder(request.source().id(), "fb2");
        try (InputStream input = request.source().openStream()) {
            XMLStreamReader reader = xmlFactory.createXMLStreamReader(input);
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
        List<ContentDocumentBuilder.ParagraphBlock> blocks = new ArrayList<>();
        String activeBlock = null;
        StringBuilder blockText = null;

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
                        if (!blocks.isEmpty()) {
                            document.addChapter(chapterId == null ? "fb2-chapter-" + (++chapterCounter) : chapterId,
                                    chapterTitle, blocks);
                            blocks = new ArrayList<>();
                        }
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
                    blockText = new StringBuilder();
                }
            } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                    && bodyDepth > 0 && activeBlock != null && blockText != null) {
                blockText.append(reader.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String local = reader.getLocalName().toLowerCase(Locale.ROOT);
                if (bodyDepth > 0 && activeBlock != null && activeBlock.equals(local) && blockText != null) {
                    String normalized = ContentDocumentBuilder.normalize(blockText.toString());
                    if (!normalized.isBlank()) {
                        if (chapterId == null) chapterId = "fb2-chapter-" + (++chapterCounter);
                        String paragraphId = "p" + (++paragraphCounter);
                        blocks.add(new ContentDocumentBuilder.ParagraphBlock(paragraphId, normalized));
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
                    if (sectionDepth == 1 && !blocks.isEmpty()) {
                        document.addChapter(chapterId, chapterTitle, blocks);
                        blocks = new ArrayList<>();
                        chapterId = null;
                        chapterTitle = "";
                    }
                    sectionDepth = Math.max(0, sectionDepth - 1);
                } else if ("body".equals(local) && bodyDepth > 0) {
                    if (!blocks.isEmpty()) {
                        if (chapterId == null) chapterId = "fb2-chapter-" + (++chapterCounter);
                        document.addChapter(chapterId, chapterTitle, blocks);
                        blocks = new ArrayList<>();
                        chapterId = null;
                        chapterTitle = "";
                    }
                    bodyDepth--;
                    sectionDepth = 0;
                    titleDepth = 0;
                }
            }
        }
        if (!blocks.isEmpty()) {
            if (chapterId == null) chapterId = "fb2-chapter-" + (++chapterCounter);
            document.addChapter(chapterId, chapterTitle, blocks);
        }
    }

    private static String normalizeFormat(String format) {
        return format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
    }
}
