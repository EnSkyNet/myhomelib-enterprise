package com.myhomelibcorp.reader.parser;

import com.myhomelibcorp.reader.model.BookDocument;
import com.myhomelibcorp.reader.model.BookMetadata;
import com.myhomelibcorp.reader.model.Chapter;
import com.myhomelibcorp.reader.model.ImageData;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
public class JsoupFb2Parser {

    // Кодування для спроби
    private static final Charset[] CHARSETS = {
            Charset.forName("UTF-8"),
            Charset.forName("Windows-1251"),
            Charset.forName("IBM866"),
            Charset.forName("KOI8-R"),
            Charset.forName("ISO-8859-5")
    };

    // Карта відповідності тегів FB2 → HTML
    private static final Map<String, String> TAG_MAP = new HashMap<>();
    static {
        TAG_MAP.put("emphasis", "em");
        TAG_MAP.put("strong", "strong");
        TAG_MAP.put("code", "code");
        TAG_MAP.put("sub", "sub");
        TAG_MAP.put("sup", "sup");
        TAG_MAP.put("strikethrough", "s");
        TAG_MAP.put("p", "p");
        TAG_MAP.put("title", "h2");
        TAG_MAP.put("subtitle", "h3");
        TAG_MAP.put("epigraph", "div");
        TAG_MAP.put("cite", "blockquote");
        TAG_MAP.put("text-author", "div");
        TAG_MAP.put("empty-line", "br");
    }

    public BookDocument parse(InputStream inputStream) throws Exception {
        long startTime = System.currentTimeMillis();

        // Читаємо всі байти
        byte[] data = inputStream.readAllBytes();

        // Визначаємо кодування
        String content = null;
        Charset usedCharset = null;

        for (Charset charset : CHARSETS) {
            try {
                String testContent = new String(data, charset);
                if (isValidContent(testContent)) {
                    content = testContent;
                    usedCharset = charset;
                    log.info("✅ Знайдено правильне кодування: {}", charset);
                    break;
                }
            } catch (Exception e) {
                // ignore
            }
        }

        if (content == null) {
            content = new String(data, StandardCharsets.UTF_8);
            usedCharset = StandardCharsets.UTF_8;
            log.warn("⚠️ Не вдалося визначити кодування, використовуємо UTF-8");
        }

        log.info("Парсинг FB2 з кодуванням: {}", usedCharset);

        // Парсимо JSoup
        Document doc = Jsoup.parse(content, "", org.jsoup.parser.Parser.xmlParser());

        Element root = doc.select("FictionBook").first();
        if (root == null) {
            log.warn("Кореневий елемент FictionBook не знайдено");
            root = doc;
        }

        log.info("Кореневий елемент: {}", root.nodeName());

        // Витягуємо метадані
        BookMetadata metadata = extractMetadata(root);

        // Витягуємо зображення
        List<ImageData> images = extractImages(root);

        // Витягуємо розділи
        List<Chapter> chapters = extractChapters(root);

        // Підраховуємо статистику
        int paragraphCount = countParagraphs(chapters);

        BookDocument document = BookDocument.builder()
                .metadata(metadata)
                .chapters(chapters)
                .images(images)
                .footnotes(new ArrayList<>())
                .build();

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("FB2 parsed: title='{}', authors={}, chapters={}, images={}, paragraphs={} ({} ms)",
                metadata.getTitle(),
                metadata.getAuthors(),
                chapters.size(),
                images.size(),
                paragraphCount,
                elapsed);

        return document;
    }

    private boolean isValidContent(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        boolean hasFictionBook = text.contains("FictionBook") || text.contains("fictionbook");
        boolean hasTitle = text.contains("book-title");
        boolean hasReplacementChar = text.contains("\uFFFD");
        boolean hasCyrillic = text.matches(".*[\\u0400-\\u04FF].*");

        if (hasFictionBook && !hasReplacementChar) {
            return true;
        }

        if (hasCyrillic && (hasFictionBook || hasTitle)) {
            return true;
        }

        return false;
    }

    // ==================== МЕТАДАНІ ====================

    private BookMetadata extractMetadata(Element root) {
        BookMetadata.BookMetadataBuilder builder = BookMetadata.builder();

        Element titleInfo = root.select("description > title-info, title-info").first();

        if (titleInfo != null) {
            // Назва
            String title = titleInfo.select("book-title").text();
            if (title == null || title.isEmpty()) {
                Element seq = titleInfo.select("sequence").first();
                if (seq != null) {
                    title = seq.attr("name");
                }
                if (title == null || title.isEmpty()) {
                    title = "Без назви";
                }
            }
            builder.title(title.trim());

            // Автори
            List<String> authors = new ArrayList<>();
            Elements authorElements = titleInfo.select("author");
            for (Element authorEl : authorElements) {
                String firstName = authorEl.select("first-name").text();
                String middleName = authorEl.select("middle-name").text();
                String lastName = authorEl.select("last-name").text();
                String fullName = (lastName != null ? lastName : "") + " " +
                        (firstName != null ? firstName : "") +
                        (middleName != null && !middleName.isEmpty() ? " " + middleName : "");
                if (!fullName.trim().isEmpty()) {
                    authors.add(fullName.trim());
                }
            }
            if (authors.isEmpty()) {
                authors.add("Невідомий автор");
            }
            builder.authors(authors);

            // Жанри
            List<String> genres = new ArrayList<>();
            Elements genreElements = titleInfo.select("genre");
            for (Element genreEl : genreElements) {
                String genre = genreEl.text().trim();
                if (!genre.isEmpty()) {
                    genres.add(genre);
                }
            }
            if (!genres.isEmpty()) {
                builder.genre(String.join(", ", genres));
            }

            // Мова
            String lang = titleInfo.select("lang").text();
            builder.language(lang != null && !lang.isEmpty() ? lang.trim() : "uk");

            // Серія
            Element seq = titleInfo.select("sequence").first();
            if (seq != null) {
                String series = seq.attr("name");
                if (series != null && !series.isEmpty()) {
                    builder.series(series.trim());
                }
                String num = seq.attr("number");
                if (num != null && !num.isEmpty()) {
                    try {
                        builder.sequenceNumber(Integer.parseInt(num.trim()));
                    } catch (NumberFormatException ignored) {}
                }
            }

            // Анотація
            String annotation = titleInfo.select("annotation").text();
            if (annotation != null && !annotation.isEmpty()) {
                builder.annotation(annotation.trim());
            }
        } else {
            builder.title("Без назви");
            builder.authors(List.of("Невідомий автор"));
            builder.language("uk");
        }

        // Видавництво
        Element publishInfo = root.select("description > publish-info, publish-info").first();
        if (publishInfo != null) {
            String publisher = publishInfo.select("publisher").text();
            if (publisher != null && !publisher.isEmpty()) {
                builder.publisher(publisher.trim());
            }
            String year = publishInfo.select("year").text();
            if (year != null && !year.isEmpty()) {
                builder.year(year.trim());
            }
        }

        return builder.build();
    }

    // ==================== ЗОБРАЖЕННЯ ====================

    private List<ImageData> extractImages(Element root) {
        List<ImageData> images = new ArrayList<>();

        // Шукаємо всі binary елементи
        Elements binaries = root.select("binary");
        for (Element binary : binaries) {
            String id = binary.attr("id");
            String contentType = binary.attr("content-type");
            String content = binary.text().trim();

            if (id != null && !id.isEmpty() && content != null && !content.isEmpty()) {
                try {
                    // Очищаємо base64
                    String cleanBase64 = content.replaceAll("\\s+", "");
                    byte[] imageData = Base64.getDecoder().decode(cleanBase64);

                    ImageData image = ImageData.builder()
                            .id(id)
                            .mimeType(contentType != null ? contentType : "image/jpeg")
                            .data(imageData)
                            .base64Data(cleanBase64)
                            .build();
                    images.add(image);
                    log.debug("Зображення завантажено: id={}, type={}, size={} KB",
                            id, contentType, imageData.length / 1024);
                } catch (Exception e) {
                    log.warn("Не вдалося декодувати зображення id={}: {}", id, e.getMessage());
                }
            }
        }

        return images;
    }

    // ==================== РОЗДІЛИ ====================

    private List<Chapter> extractChapters(Element root) {
        List<Chapter> chapters = new ArrayList<>();

        // Шукаємо body
        Element body = root.select("body").first();
        if (body == null) {
            log.warn("Body не знайдено");
            return chapters;
        }

        // Шукаємо секції
        Elements sections = body.select("section");
        int paragraphCounter = 0;

        if (sections.isEmpty()) {
            // Немає секцій - весь текст в одному розділі
            Chapter singleChapter = processDirectBody(body, new Counter(paragraphCounter));
            if (singleChapter != null && singleChapter.getContent() != null && !singleChapter.getContent().isEmpty()) {
                chapters.add(singleChapter);
            }
        } else {
            // Обробляємо кожну секцію
            for (Element section : sections) {
                Counter counter = new Counter(paragraphCounter);
                Chapter chapter = processSection(section, 1, counter);
                if (chapter != null) {
                    chapters.add(chapter);
                    paragraphCounter = counter.value;
                }
            }
        }

        // Якщо розділів немає - створюємо один
        if (chapters.isEmpty()) {
            Chapter defaultChapter = Chapter.builder()
                    .id(UUID.randomUUID().toString())
                    .title("Розділ")
                    .level(1)
                    .content("<p>Немає тексту для відображення.</p>")
                    .build();
            chapters.add(defaultChapter);
            log.warn("Створено розділ за замовчуванням");
        }

        log.info("Знайдено секцій: {}, параграфів: {}", chapters.size(), paragraphCounter);
        return chapters;
    }

    private Chapter processDirectBody(Element body, Counter counter) {
        StringBuilder content = new StringBuilder();
        Elements children = body.children();

        for (Element child : children) {
            String tag = child.tagName().toLowerCase();
            processElement(child, tag, content, counter, 1);
        }

        if (content.length() == 0) {
            return null;
        }

        return Chapter.builder()
                .id(UUID.randomUUID().toString())
                .title("Розділ")
                .level(1)
                .content(content.toString())
                .children(new ArrayList<>())
                .build();
    }

    private Chapter processSection(Element section, int level, Counter counter) {
        // Визначаємо назву розділу
        String title = "Розділ";
        Element titleEl = section.select("title").first();
        if (titleEl != null) {
            String t = titleEl.text();
            if (t != null && !t.trim().isEmpty()) {
                title = t.trim();
            }
        }

        StringBuilder content = new StringBuilder();
        Elements children = section.children();

        for (Element child : children) {
            String tag = child.tagName().toLowerCase();

            if ("section".equals(tag)) {
                // Вкладений розділ
                Chapter subChapter = processSection(child, level + 1, counter);
                if (subChapter != null && subChapter.getContent() != null && !subChapter.getContent().isEmpty()) {
                    content.append(subChapter.getContent());
                }
            } else {
                processElement(child, tag, content, counter, level);
            }
        }

        if (content.length() == 0) {
            return null;
        }

        return Chapter.builder()
                .id(UUID.randomUUID().toString())
                .title(title)
                .level(Math.min(level, 6))
                .content(content.toString())
                .children(new ArrayList<>())
                .build();
    }

    // ==================== ОБРОБКА ЕЛЕМЕНТІВ ====================

    private void processElement(Element element, String tag, StringBuilder content, Counter counter, int level) {
        switch (tag) {
            case "p":
                processParagraph(element, content, counter);
                break;
            case "title":
                processTitle(element, content, level);
                break;
            case "subtitle":
                processSubtitle(element, content);
                break;
            case "epigraph":
                processEpigraph(element, content);
                break;
            case "cite":
                processCite(element, content);
                break;
            case "poem":
                processPoem(element, content);
                break;
            case "text-author":
                processTextAuthor(element, content);
                break;
            case "empty-line":
                content.append("<br/>\n");
                break;
            case "emphasis":
                processInline(element, "em", content);
                break;
            case "strong":
                processInline(element, "strong", content);
                break;
            case "code":
                processInline(element, "code", content);
                break;
            case "sub":
                processInline(element, "sub", content);
                break;
            case "sup":
                processInline(element, "sup", content);
                break;
            case "strikethrough":
                processInline(element, "s", content);
                break;
            case "image":
                processImage(element, content);
                break;
            default:
                // Інші теги - просто текст
                String text = element.text();
                if (text != null && !text.trim().isEmpty()) {
                    content.append("<p>").append(escapeHtml(text.trim())).append("</p>\n");
                }
                break;
        }
    }

    // ==================== ПАРАГРАФ ====================

    private void processParagraph(Element element, StringBuilder content, Counter counter) {
        String html = processElementContent(element);
        if (html != null && !html.trim().isEmpty()) {
            String pId = "p" + (++counter.value);
            content.append("<p data-paragraph-id=\"").append(pId).append("\">")
                    .append(html)
                    .append("</p>\n");
        }
    }

    // ==================== ЗАГОЛОВКИ ====================

    private void processTitle(Element element, StringBuilder content, int level) {
        String text = element.text();
        if (text != null && !text.trim().isEmpty()) {
            int headingLevel = Math.min(level + 1, 6);
            content.append("<h").append(headingLevel)
                    .append(" class=\"chapter-title\">")
                    .append(escapeHtml(text.trim()))
                    .append("</h").append(headingLevel).append(">\n");
        }
    }

    private void processSubtitle(Element element, StringBuilder content) {
        String text = element.text();
        if (text != null && !text.trim().isEmpty()) {
            content.append("<h3 class=\"subtitle\">")
                    .append(escapeHtml(text.trim()))
                    .append("</h3>\n");
        }
    }

    // ==================== ЕПІГРАФ ====================

    private void processEpigraph(Element element, StringBuilder content) {
        content.append("<div class=\"epigraph\">\n");

        // Обробляємо текст всередині епіграфа
        Elements children = element.children();
        for (Element child : children) {
            String tag = child.tagName().toLowerCase();
            if ("p".equals(tag)) {
                String text = child.text();
                if (text != null && !text.trim().isEmpty()) {
                    content.append("<p>").append(escapeHtml(text.trim())).append("</p>\n");
                }
            } else if ("cite".equals(tag)) {
                String text = child.text();
                if (text != null && !text.trim().isEmpty()) {
                    content.append("<cite>").append(escapeHtml(text.trim())).append("</cite>\n");
                }
            } else if ("text-author".equals(tag)) {
                String text = child.text();
                if (text != null && !text.trim().isEmpty()) {
                    content.append("<div class=\"epigraph-author\">")
                            .append(escapeHtml(text.trim()))
                            .append("</div>\n");
                }
            } else {
                String text = child.text();
                if (text != null && !text.trim().isEmpty()) {
                    content.append("<p>").append(escapeHtml(text.trim())).append("</p>\n");
                }
            }
        }

        content.append("</div>\n");
    }

    // ==================== ЦИТАТА ====================

    private void processCite(Element element, StringBuilder content) {
        String html = processElementContent(element);
        if (html != null && !html.trim().isEmpty()) {
            content.append("<blockquote>")
                    .append(html)
                    .append("</blockquote>\n");
        }
    }

    // ==================== ПОЕЗІЯ ====================

    private void processPoem(Element element, StringBuilder content) {
        content.append("<div class=\"poem\">\n");

        Elements children = element.children();
        for (Element child : children) {
            String tag = child.tagName().toLowerCase();
            if ("stanza".equals(tag)) {
                content.append("<div class=\"stanza\">\n");
                Elements verses = child.children();
                for (Element verse : verses) {
                    String vTag = verse.tagName().toLowerCase();
                    if ("v".equals(vTag)) {
                        String text = verse.html();
                        if (text != null && !text.trim().isEmpty()) {
                            // Зберігаємо пробіли для поезії
                            content.append("<div class=\"verse\">")
                                    .append(text.replace("\n", "<br/>"))
                                    .append("</div>\n");
                        }
                    } else {
                        String text = verse.text();
                        if (text != null && !text.trim().isEmpty()) {
                            content.append("<div class=\"verse\">")
                                    .append(escapeHtml(text.trim()))
                                    .append("</div>\n");
                        }
                    }
                }
                content.append("</div>\n");
            } else if ("title".equals(tag)) {
                String text = child.text();
                if (text != null && !text.trim().isEmpty()) {
                    content.append("<div class=\"poem-title\">")
                            .append(escapeHtml(text.trim()))
                            .append("</div>\n");
                }
            } else if ("text-author".equals(tag)) {
                String text = child.text();
                if (text != null && !text.trim().isEmpty()) {
                    content.append("<div class=\"poem-author\">")
                            .append(escapeHtml(text.trim()))
                            .append("</div>\n");
                }
            } else {
                String text = child.text();
                if (text != null && !text.trim().isEmpty()) {
                    content.append("<div class=\"verse\">")
                            .append(escapeHtml(text.trim()))
                            .append("</div>\n");
                }
            }
        }

        content.append("</div>\n");
    }

    // ==================== АВТОР ТЕКСТУ ====================

    private void processTextAuthor(Element element, StringBuilder content) {
        String text = element.text();
        if (text != null && !text.trim().isEmpty()) {
            content.append("<div class=\"text-author\">")
                    .append(escapeHtml(text.trim()))
                    .append("</div>\n");
        }
    }

    // ==================== INLINE ТЕГИ ====================

    private void processInline(Element element, String htmlTag, StringBuilder content) {
        String html = processElementContent(element);
        if (html != null && !html.trim().isEmpty()) {
            content.append("<").append(htmlTag).append(">")
                    .append(html)
                    .append("</").append(htmlTag).append(">");
        }
    }

    // ==================== ЗОБРАЖЕННЯ ====================

    private void processImage(Element element, StringBuilder content) {
        // Шукаємо href або src
        String href = element.attr("href");
        if (href == null || href.isEmpty()) {
            href = element.attr("xlink:href");
        }
        if (href == null || href.isEmpty()) {
            href = element.attr("src");
        }

        if (href != null && href.startsWith("#")) {
            String imageId = href.substring(1);
            // Зображення буде вставлено через DocumentToHtmlConverter
            content.append("<img data-image-id=\"").append(escapeHtml(imageId))
                    .append("\" src=\"data:image/jpeg;base64,PLACEHOLDER\" alt=\"Зображення\"/>");
        } else if (href != null && !href.isEmpty()) {
            content.append("<img src=\"").append(escapeHtml(href))
                    .append("\" alt=\"Зображення\"/>");
        }
    }

    // ==================== ДОПОМІЖНІ МЕТОДИ ====================

    private String processElementContent(Element element) {
        if (element == null) {
            return null;
        }

        StringBuilder result = new StringBuilder();
        for (Node node : element.childNodes()) {
            if (node instanceof TextNode) {
                String text = ((TextNode) node).text();
                if (text != null) {
                    result.append(escapeHtml(text));
                }
            } else if (node instanceof Element) {
                Element child = (Element) node;
                String tag = child.tagName().toLowerCase();
                String htmlTag = TAG_MAP.getOrDefault(tag, tag);

                // Рекурсивно обробляємо вкладені елементи
                String innerHtml = processElementContent(child);
                if (innerHtml != null && !innerHtml.trim().isEmpty()) {
                    result.append("<").append(htmlTag).append(">")
                            .append(innerHtml)
                            .append("</").append(htmlTag).append(">");
                } else {
                    // Якщо немає вмісту - просто текст
                    String text = child.text();
                    if (text != null && !text.trim().isEmpty()) {
                        result.append(escapeHtml(text.trim()));
                    }
                }
            }
        }

        return result.toString();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("\n", "<br/>");
    }

    private int countParagraphs(List<Chapter> chapters) {
        int count = 0;
        for (Chapter chapter : chapters) {
            count += countParagraphsInChapter(chapter);
        }
        return count;
    }

    private int countParagraphsInChapter(Chapter chapter) {
        int count = 0;
        if (chapter.getContent() != null) {
            // Рахуємо <p> теги
            count += chapter.getContent().split("<p ").length - 1;
        }
        for (Chapter child : chapter.getChildren()) {
            count += countParagraphsInChapter(child);
        }
        return count;
    }

    private static class Counter {
        int value;
        Counter(int value) { this.value = value; }
    }
}