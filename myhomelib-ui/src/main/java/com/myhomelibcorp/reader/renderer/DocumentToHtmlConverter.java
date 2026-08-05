package com.myhomelibcorp.reader.renderer;

import com.myhomelibcorp.reader.model.BookDocument;
import com.myhomelibcorp.reader.model.BookMetadata;
import com.myhomelibcorp.reader.model.Chapter;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

@Slf4j
public class DocumentToHtmlConverter {

    private static final Safelist HTML_WHITELIST = Safelist.basic()
            .addTags("h1", "h2", "h3", "h4", "h5", "h6", "div", "span", "br",
                    "b", "i", "strong", "em", "u", "s", "sub", "sup", "code", "pre",
                    "blockquote", "q", "ul", "ol", "li", "hr", "img")
            .addAttributes("img", "src", "alt", "width", "height")
            .addAttributes("p", "data-paragraph-id")
            .addAttributes("div", "class")
            .addAttributes("span", "class");

    public String convert(BookDocument document) {
        long startTime = System.currentTimeMillis();

        BookMetadata metadata = document.getMetadata();

        StringBuilder html = new StringBuilder(1024 * 100);

        html.append("<!DOCTYPE html>\n");
        html.append("<html>\n");
        html.append("<head>\n");
        html.append("    <meta charset=\"UTF-8\"/>\n");
        html.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>\n");
        html.append("    <title>").append(escapeHtml(metadata.getTitle())).append("</title>\n");
        html.append("</head>\n");
        html.append("<body>\n");

        html.append("<div class=\"book-title\">").append(escapeHtml(metadata.getTitle())).append("</div>\n");

        if (metadata.getAuthors() != null && !metadata.getAuthors().isEmpty()) {
            html.append("<div class=\"authors\">");
            html.append(String.join(", ", metadata.getAuthors().stream().map(this::escapeHtml).toArray(String[]::new)));
            html.append("</div>\n");
        }

        if (metadata.getAnnotation() != null && !metadata.getAnnotation().isEmpty()) {
            html.append("<div class=\"annotation\">");
            html.append(escapeHtml(metadata.getAnnotation()));
            html.append("</div>\n");
        }

        if (metadata.getSeries() != null) {
            html.append("<div class=\"series-info\">Серія: ").append(escapeHtml(metadata.getSeries()));
            if (metadata.getSequenceNumber() != null && metadata.getSequenceNumber() > 0) {
                html.append(" #").append(metadata.getSequenceNumber());
            }
            html.append("</div>\n");
        }

        html.append("<hr class=\"book-divider\"/>\n");

        for (Chapter chapter : document.getChapters()) {
            html.append(renderChapter(chapter, 1));
        }

        html.append("</body>\n");
        html.append("</html>");

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("HTML generated: {} chars, {} ms", html.length(), elapsed);

        return html.toString();
    }

    private String renderChapter(Chapter chapter, int level) {
        StringBuilder sb = new StringBuilder(1024);
        String tag = "h" + Math.min(level, 6);

        sb.append("<div class=\"chapter\">\n");
        if (chapter.getTitle() != null && !chapter.getTitle().isEmpty()) {
            sb.append("<").append(tag).append(" class=\"chapter-title\">")
                    .append(escapeHtml(chapter.getTitle()))
                    .append("</").append(tag).append(">\n");
        }
        if (chapter.getContent() != null && !chapter.getContent().isEmpty()) {
            sb.append("<div class=\"chapter-content\">\n");
            String safeContent = Jsoup.clean(chapter.getContent(), HTML_WHITELIST);
            sb.append(safeContent);
            sb.append("</div>\n");
        }
        for (Chapter child : chapter.getChildren()) {
            sb.append(renderChapter(child, level + 1));
        }
        sb.append("</div>\n");
        return sb.toString();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("\n", "<br/>");
    }
}