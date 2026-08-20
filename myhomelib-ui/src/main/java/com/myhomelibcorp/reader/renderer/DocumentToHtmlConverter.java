package com.myhomelibcorp.reader.renderer;

import com.myhomelibcorp.reader.model.BookDocument;
import com.myhomelibcorp.reader.model.BookMetadata;
import com.myhomelibcorp.reader.model.Chapter;
import com.myhomelibcorp.reader.model.ImageData;
import com.myhomelibcorp.reader.service.ImageCacheService;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class DocumentToHtmlConverter {

    // ===== ВИПРАВЛЕНО: ДОДАНО АТРИБУТИ ДЛЯ ANCHOR =====
    private static final Safelist HTML_WHITELIST = Safelist.basic()
            .addTags("h1", "h2", "h3", "h4", "h5", "h6", "div", "span", "br",
                    "b", "i", "strong", "em", "u", "s", "sub", "sup", "code", "pre",
                    "blockquote", "q", "ul", "ol", "li", "hr", "img", "a")
            .addAttributes("img", "src", "alt", "width", "height", "data-image-id", "data-cache-key")
            // ===== КЛЮЧОВА ЗМІНА: ДОДАНО data-anchor-id =====
            .addAttributes("p",
                    "data-paragraph-id",
                    "data-anchor-id",      // СТАБІЛЬНИЙ ІДЕНТИФІКАТОР
                    "data-xpath",
                    "data-paragraph-index")
            .addAttributes("div", "class")
            .addAttributes("span", "class")
            .addAttributes("a", "href", "class", "data-note-id", "target");

    private final ImageCacheService imageCache;

    public DocumentToHtmlConverter() {
        this.imageCache = new ImageCacheService();
    }

    public DocumentToHtmlConverter(ImageCacheService imageCache) {
        this.imageCache = imageCache;
    }

    public String convert(BookDocument document) {
        long startTime = System.currentTimeMillis();

        BookMetadata metadata = document.getMetadata();

        Map<String, ImageData> imageMap = document.getImages().stream()
                .collect(Collectors.toMap(ImageData::getId, img -> img));

        log.info("📚 Converting book: {}, images: {}", metadata.getTitle(), imageMap.size());

        StringBuilder html = new StringBuilder(1024 * 100);

        html.append("<!DOCTYPE html>\n");
        html.append("<html>\n");
        html.append("<head>\n");
        html.append("    <meta charset=\"UTF-8\"/>\n");
        html.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>\n");
        html.append("    <title>").append(escapeHtml(metadata.getTitle())).append("</title>\n");

        html.append("    <style>\n");
        html.append("        img { max-width: 100%; height: auto; display: block; margin: 10px auto; border-radius: 4px; }\n");
        html.append("        img.loading { opacity: 0.5; filter: blur(2px); }\n");
        html.append("        img.loaded { opacity: 1; transition: opacity 0.3s ease; }\n");
        html.append("        .footnote { font-size: 0.9em; color: #666; margin: 10px 0; padding: 8px 12px; border-left: 3px solid #ccc; background: #f9f9f9; border-radius: 4px; }\n");
        html.append("        .footnote-ref { color: #2196F3; text-decoration: none; font-size: 0.75em; vertical-align: super; padding: 0 2px; cursor: pointer; }\n");
        html.append("        .footnote-ref:hover { text-decoration: underline; }\n");
        html.append("        .internal-link { color: #2196F3; text-decoration: none; border-bottom: 1px dotted #2196F3; }\n");
        html.append("        .internal-link:hover { text-decoration: underline; }\n");
        html.append("        .external-link { color: #2196F3; text-decoration: none; }\n");
        html.append("        .external-link:hover { text-decoration: underline; }\n");
        html.append("        .external-link::after { content: ' ↗'; font-size: 0.8em; }\n");
        html.append("        .notes-section { margin-top: 30px; padding-top: 20px; border-top: 2px solid #e0e0e0; }\n");
        html.append("        .notes-section h2 { font-size: 1.2em; color: #333; }\n");
        html.append("        .note-number { font-weight: bold; color: #555; margin-right: 5px; }\n");
        html.append("        .back-to-text { font-size: 0.8em; color: #999; margin-left: 8px; text-decoration: none; }\n");
        html.append("        .back-to-text:hover { color: #666; }\n");
        html.append("        .footnote-target { scroll-margin-top: 20px; }\n");
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

        String content = renderChapters(document.getChapters(), 1, imageMap);
        html.append(content);

        html.append("</body>\n");
        html.append("</html>");

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("HTML generated: {} chars, {} ms, image cache: {}",
                html.length(), elapsed, imageCache.getStats());

        return html.toString();
    }

    private String renderChapters(List<Chapter> chapters, int level, Map<String, ImageData> imageMap) {
        StringBuilder sb = new StringBuilder(1024 * 100);
        for (Chapter chapter : chapters) {
            sb.append(renderChapter(chapter, level, imageMap));
        }
        return sb.toString();
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

            // ===== ОБРОБКА ЗОБРАЖЕНЬ =====
            if (imageMap != null && !imageMap.isEmpty() && content.contains("PLACEHOLDER")) {
                int totalReplaced = 0;
                for (Map.Entry<String, ImageData> entry : imageMap.entrySet()) {
                    String imageId = entry.getKey();
                    ImageData image = entry.getValue();

                    if (image == null || image.getData() == null || image.getData().length == 0) {
                        continue;
                    }

                    // Перевіряємо, чи є цей imageId в контенті
                    String searchId = "data-image-id=\"" + imageId + "\"";
                    if (!content.contains(searchId)) {
                        continue;
                    }

                    // Кешуємо зображення
                    String cacheKey = "img_" + imageId;
                    byte[] cachedData = imageCache.get(cacheKey);
                    if (cachedData == null) {
                        imageCache.put(cacheKey, image.getData(), image.getMimeType());
                        cachedData = image.getData();
                    }

                    // Конвертуємо в Base64
                    String base64 = Base64.getEncoder().encodeToString(cachedData);
                    String mimeType = imageCache.getMimeType(cacheKey);
                    if (mimeType == null) {
                        mimeType = image.getMimeType() != null ? image.getMimeType() : "image/jpeg";
                    }

                    String dataUri = "data:" + mimeType + ";base64," + base64;

                    // ВАРІАНТ 1: точний збіг для JPEG
                    String oldTag1 = "data-image-id=\"" + imageId + "\" src=\"data:image/jpeg;base64,PLACEHOLDER\"";
                    if (content.contains(oldTag1)) {
                        content = content.replace(oldTag1, "data-image-id=\"" + imageId + "\" data-cache-key=\"" + cacheKey + "\" src=\"" + dataUri + "\"");
                        totalReplaced++;
                        continue;
                    }

                    // ВАРІАНТ 2: точний збіг для PNG
                    String oldTag2 = "data-image-id=\"" + imageId + "\" src=\"data:image/png;base64,PLACEHOLDER\"";
                    if (content.contains(oldTag2)) {
                        content = content.replace(oldTag2, "data-image-id=\"" + imageId + "\" data-cache-key=\"" + cacheKey + "\" src=\"" + dataUri + "\"");
                        totalReplaced++;
                        continue;
                    }

                    // ВАРІАНТ 3: будь-який src з PLACEHOLDER
                    String searchPattern = "data-image-id=\"" + imageId + "\" src=\"[^\"]*PLACEHOLDER[^\"]*\"";
                    java.util.regex.Pattern p = java.util.regex.Pattern.compile(searchPattern);
                    java.util.regex.Matcher m = p.matcher(content);
                    if (m.find()) {
                        String replacement = "data-image-id=\"" + imageId + "\" data-cache-key=\"" + cacheKey + "\" src=\"" + dataUri + "\"";
                        content = m.replaceAll(replacement);
                        totalReplaced++;
                        continue;
                    }

                    // ВАРІАНТ 4: простий пошук і заміна всього тега
                    String simpleSearch = "data-image-id=\"" + imageId + "\"";
                    int startIdx = content.indexOf(simpleSearch);
                    if (startIdx != -1) {
                        int endIdx = content.indexOf("/>", startIdx);
                        if (endIdx == -1) {
                            endIdx = content.indexOf(">", startIdx);
                        }
                        if (endIdx != -1) {
                            String oldTag = content.substring(startIdx, endIdx + 2);
                            String newTag = "data-image-id=\"" + imageId + "\" data-cache-key=\"" + cacheKey + "\" src=\"" + dataUri + "\" />";
                            content = content.replace(oldTag, newTag);
                            totalReplaced++;
                        }
                    }
                }

                if (totalReplaced > 0) {
                    log.debug("🖼️ Replaced {} images in chapter '{}'", totalReplaced, chapter.getTitle());
                }
            }

            // ===== ВИПРАВЛЕНО: SAFELIST ЗБЕРІГАЄ data-anchor-id =====
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

    public ImageCacheService getImageCache() {
        return imageCache;
    }
}