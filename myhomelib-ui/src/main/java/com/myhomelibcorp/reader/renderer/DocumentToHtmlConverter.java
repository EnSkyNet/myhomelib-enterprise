package com.myhomelibcorp.reader.renderer;

import com.myhomelibcorp.reader.model.BookDocument;
import com.myhomelibcorp.reader.model.BookMetadata;
import com.myhomelibcorp.reader.model.Chapter;
import com.myhomelibcorp.reader.model.ImageData;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class DocumentToHtmlConverter {

    private static final Safelist HTML_WHITELIST = Safelist.basic()
            .addTags("h1", "h2", "h3", "h4", "h5", "h6", "div", "span", "br",
                    "b", "i", "strong", "em", "u", "s", "sub", "sup", "code", "pre",
                    "blockquote", "q", "ul", "ol", "li", "hr", "img", "a")
            .addAttributes("img", "src", "alt", "width", "height", "data-image-id")
            .addAttributes("p", "data-paragraph-id")
            .addAttributes("div", "class")
            .addAttributes("span", "class")
            .addAttributes("a", "href", "class", "data-footnote-id", "data-note-id");

    public String convert(BookDocument document) {
        long startTime = System.currentTimeMillis();

        BookMetadata metadata = document.getMetadata();

        Map<String, ImageData> imageMap = document.getImages().stream()
                .collect(Collectors.toMap(ImageData::getId, img -> img));

        StringBuilder html = new StringBuilder(1024 * 100);

        html.append("<!DOCTYPE html>\n");
        html.append("<html>\n");
        html.append("<head>\n");
        html.append("    <meta charset=\"UTF-8\"/>\n");
        html.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>\n");
        html.append("    <title>").append(escapeHtml(metadata.getTitle())).append("</title>\n");

        // Стилі для приміток
        html.append("    <style>\n");
        html.append("        .footnote { font-size: 0.9em; color: #666; margin: 10px 0; padding: 8px 12px; border-left: 3px solid #ccc; }\n");
        html.append("        .footnote-ref { color: #2196F3; text-decoration: none; font-size: 0.8em; vertical-align: super; }\n");
        html.append("        .footnote-ref:hover { text-decoration: underline; }\n");
        html.append("        a[data-note-id] { color: #2196F3; text-decoration: none; }\n");
        html.append("        a[data-note-id]:hover { text-decoration: underline; }\n");
        html.append("    </style>\n");

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

        // Додаємо секцію для приміток в кінці
        StringBuilder footnotesHtml = new StringBuilder();
        footnotesHtml.append("<div class=\"footnotes-section\">\n");
        footnotesHtml.append("<h3>Примітки</h3>\n");

        for (Chapter chapter : document.getChapters()) {
            html.append(renderChapter(chapter, 1, imageMap, footnotesHtml));
        }

        html.append(footnotesHtml.toString());
        html.append("</div>\n");

        html.append("</body>\n");
        html.append("</html>");

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("HTML generated: {} chars, {} ms", html.length(), elapsed);

        return html.toString();
    }

    private String renderChapter(Chapter chapter, int level, Map<String, ImageData> imageMap, StringBuilder footnotes) {
        StringBuilder sb = new StringBuilder(1024);
        String tag = "h" + Math.min(level, 6);

        sb.append("<div class=\"chapter\">\n");

        if (chapter.getTitle() != null && !chapter.getTitle().isEmpty()) {
            sb.append("<").append(tag).append(" class=\"chapter-title\">")
                    .append(escapeHtml(chapter.getTitle()))
                    .append("</").append(tag).append(">\n");
        }

        if (chapter.getContent() != null && !chapter.getContent().isEmpty()) {
            String content = chapter.getContent();

            // Підставляємо зображення
            if (imageMap != null && !imageMap.isEmpty()) {
                for (Map.Entry<String, ImageData> entry : imageMap.entrySet()) {
                    String imageId = entry.getKey();
                    ImageData image = entry.getValue();
                    if (image != null && image.getData() != null) {
                        String base64 = Base64.getEncoder().encodeToString(image.getData());
                        String placeholder = "data:image/jpeg;base64,PLACEHOLDER";
                        content = content.replace(
                                "data-image-id=\"" + imageId + "\" src=\"" + placeholder + "\"",
                                "data-image-id=\"" + imageId + "\" src=\"data:" + image.getMimeType() + ";base64," + base64 + "\""
                        );
                    }
                }
            }

            // Обробляємо примітки
            content = processFootnotes(content, footnotes);

            String safeContent = Jsoup.clean(content, HTML_WHITELIST);
            sb.append("<div class=\"chapter-content\">\n");
            sb.append(safeContent);
            sb.append("</div>\n");
        }

        for (Chapter child : chapter.getChildren()) {
            sb.append(renderChapter(child, level + 1, imageMap, footnotes));
        }

        sb.append("</div>\n");
        return sb.toString();
    }

    private String processFootnotes(String content, StringBuilder footnotes) {
        // Шукаємо посилання на примітки у форматі [1], [2], тощо
        // та замінюємо їх на HTML-посилання
        String result = content;

        // Проста обробка: шукаємо [цифра]
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\[(\\d+)\\]");
        java.util.regex.Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            String noteId = matcher.group(1);
            String replacement = "<a href=\"#note-" + noteId + "\" data-note-id=\"" + noteId + "\" class=\"footnote-ref\">[" + noteId + "]</a>";
            result = result.replace("[" + noteId + "]", replacement);

            // Додаємо примітку в кінець
            String noteContent = getNoteContent(content, noteId);
            if (noteContent != null) {
                footnotes.append("<div class=\"footnote\" id=\"note-").append(noteId).append("\">");
                footnotes.append("<a href=\"#note-ref-").append(noteId).append("\">↩</a> ");
                footnotes.append(noteContent);
                footnotes.append("</div>\n");
            }
        }

        return result;
    }

    private String getNoteContent(String content, String noteId) {
        // Спрощена реалізація - в реальному проекті потрібен парсинг FB2
        return "Примітка " + noteId;
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