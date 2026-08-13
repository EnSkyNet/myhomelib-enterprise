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
                    "blockquote", "q", "ul", "ol", "li", "hr", "img")
            .addAttributes("img", "src", "alt", "width", "height", "data-image-id")
            .addAttributes("p", "data-paragraph-id")
            .addAttributes("div", "class")
            .addAttributes("span", "class");

    public String convert(BookDocument document) {
        long startTime = System.currentTimeMillis();

        BookMetadata metadata = document.getMetadata();

        // Створюємо карту зображень для швидкого доступу
        Map<String, ImageData> imageMap = document.getImages().stream()
                .collect(Collectors.toMap(ImageData::getId, img -> img));

        StringBuilder html = new StringBuilder(1024 * 100);

        html.append("<!DOCTYPE html>\n");
        html.append("<html>\n");
        html.append("<head>\n");
        html.append("    <meta charset=\"UTF-8\"/>\n");
        html.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>\n");
        html.append("    <title>").append(escapeHtml(metadata.getTitle())).append("</title>\n");
        html.append("</head>\n");
        html.append("<body>\n");

        // Заголовок
        html.append("<div class=\"book-title\">").append(escapeHtml(metadata.getTitle())).append("</div>\n");

        // Автори
        if (metadata.getAuthors() != null && !metadata.getAuthors().isEmpty()) {
            html.append("<div class=\"authors\">");
            html.append(String.join(", ", metadata.getAuthors().stream().map(this::escapeHtml).toArray(String[]::new)));
            html.append("</div>\n");
        }

        // Анотація
        if (metadata.getAnnotation() != null && !metadata.getAnnotation().isEmpty()) {
            html.append("<div class=\"annotation\">");
            html.append(escapeHtml(metadata.getAnnotation()));
            html.append("</div>\n");
        }

        // Серія
        if (metadata.getSeries() != null) {
            html.append("<div class=\"series-info\">Серія: ").append(escapeHtml(metadata.getSeries()));
            if (metadata.getSequenceNumber() != null && metadata.getSequenceNumber() > 0) {
                html.append(" #").append(metadata.getSequenceNumber());
            }
            html.append("</div>\n");
        }

        html.append("<hr class=\"book-divider\"/>\n");

        // Розділи
        for (Chapter chapter : document.getChapters()) {
            html.append(renderChapter(chapter, 1, imageMap));
        }

        html.append("</body>\n");
        html.append("</html>");

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("HTML generated: {} chars, {} ms", html.length(), elapsed);

        return html.toString();
    }

    private String renderChapter(Chapter chapter, int level, Map<String, ImageData> imageMap) {
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

            // Замінюємо плейсхолдери зображень на реальні base64
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

            String safeContent = Jsoup.clean(content, HTML_WHITELIST);
            sb.append("<div class=\"chapter-content\">\n");
            sb.append(safeContent);
            sb.append("</div>\n");
        }

        for (Chapter child : chapter.getChildren()) {
            sb.append(renderChapter(child, level + 1, imageMap));
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