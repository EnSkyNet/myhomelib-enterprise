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

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
public class JsoupFb2Parser {

    private static final Charset[] CHARSETS = {
            Charset.forName("UTF-8"),
            Charset.forName("Windows-1251"),
            Charset.forName("IBM866"),
            Charset.forName("KOI8-R"),
            Charset.forName("ISO-8859-5")
    };

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

    // Регулярний вираз для пошуку paragraphId
    private static final Pattern PARAGRAPH_ID_PATTERN = Pattern.compile("data-paragraph-id=\"([^\"]+)\"");

    public BookDocument parse(InputStream inputStream) throws Exception {
        long startTime = System.currentTimeMillis();

        byte[] data = inputStream.readAllBytes();

        String content = null;
        Charset usedCharset = null;

        for (Charset charset : CHARSETS) {
            try {
                String testContent = new String(data, charset);
                if (isValidContent(testContent)) {
                    content = testContent;
                    usedCharset = charset;
                    log.info("Знайдено правильне кодування: {}", charset);
                    break;
                }
            } catch (Exception e) {
                // ignore
            }
        }

        if (content == null) {
            content = new String(data, StandardCharsets.UTF_8);
            usedCharset = StandardCharsets.UTF_8;
            log.warn("Не вдалося визначити кодування, використовую UTF-8");
        }

        log.info("Парсинг FB2 з кодуванням: {}", usedCharset);

        Document doc = Jsoup.parse(content, "", org.jsoup.parser.Parser.xmlParser());

        Element root = doc.select("FictionBook").first();
        if (root == null) {
            log.warn("Кореневий елемент FictionBook не знайдено");
            root = doc;
        }

        BookMetadata metadata = extractMetadata(root);
        List<ImageData> images = extractImages(root);
        List<Chapter> chapters = extractChapters(root);

        int paragraphCount = countParagraphs(chapters);

        BookDocument document = BookDocument.builder()
                .metadata(metadata)
                .chapters(chapters)
                .images(images)
                .build();

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("FB2 розпарсено: title='{}', chapters={}, images={}, paragraphs={} ({} ms)",
                metadata.getTitle(), chapters.size(), images.size(), paragraphCount, elapsed);

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
        return hasCyrillic && (hasFictionBook || hasTitle);
    }

    private BookMetadata extractMetadata(Element root) {
        BookMetadata.BookMetadataBuilder builder = BookMetadata.builder();

        Element titleInfo = root.select("description > title-info, title-info").first();

        if (titleInfo != null) {
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

            String lang = titleInfo.select("lang").text();
            builder.language(lang != null && !lang.isEmpty() ? lang.trim() : "uk");

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

            String annotation = titleInfo.select("annotation").text();
            if (annotation != null && !annotation.isEmpty()) {
                builder.annotation(annotation.trim());
            }
        } else {
            builder.title("Без назви");
            builder.authors(List.of("Невідомий автор"));
            builder.language("uk");
        }

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

    private List<ImageData> extractImages(Element root) {
        List<ImageData> images = new ArrayList<>();

        Elements binaries = root.select("binary");
        for (Element binary : binaries) {
            String id = binary.attr("id");
            String contentType = binary.attr("content-type");
            String content = binary.text().trim();

            if (id != null && !id.isEmpty() && content != null && !content.isEmpty()) {
                try {
                    String cleanBase64 = content.replaceAll("\\s+", "");
                    byte[] imageData = Base64.getDecoder().decode(cleanBase64);

                    ImageData image = ImageData.builder()
                            .id(id)
                            .mimeType(contentType != null ? contentType : "image/jpeg")
                            .data(imageData)
                            .build();
                    images.add(image);
                    log.debug("Завантажено зображення: id={}, type={}, size={} KB",
                            id, contentType, imageData.length / 1024);
                } catch (Exception e) {
                    log.warn("Не вдалося декодувати зображення id={}: {}", id, e.getMessage());
                }
            }
        }

        return images;
    }

    private List<Chapter> extractChapters(Element root) {
        List<Chapter> chapters = new ArrayList<>();

        Element body = root.select("body").first();
        if (body == null) {
            log.warn("Body не знайдено");
            return chapters;
        }

        List<Element> topLevelSections = body.children().stream()
                .filter(element -> "section".equalsIgnoreCase(element.tagName()))
                .toList();

        int paragraphCounter = 0;

        if (topLevelSections.isEmpty()) {
            Chapter singleChapter = processDirectBody(body, new Counter(paragraphCounter));
            if (singleChapter != null && singleChapter.getContent() != null && !singleChapter.getContent().isEmpty()) {
                chapters.add(singleChapter);
            }
        } else {
            Counter counter = new Counter(paragraphCounter);
            for (Element section : topLevelSections) {
                Chapter chapter = processSection(section, 1, counter);
                if (chapter != null) {
                    chapters.add(chapter);
                    paragraphCounter = counter.value;
                }
            }
        }

        if (chapters.isEmpty()) {
            Chapter defaultChapter = Chapter.builder()
                    .id(UUID.randomUUID().toString())
                    .title("Зміст")
                    .level(1)
                    .content("<p>Немає тексту для відображення.</p>")
                    .paragraphId("p1")
                    .build();
            chapters.add(defaultChapter);
            log.warn("Створено дефолтний розділ");
        }

        log.info("Знайдено розділів: {}, параграфів: {}", chapters.size(), paragraphCounter);
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

        // Шукаємо перший paragraphId у контенті
        String paragraphId = findFirstParagraphId(content);

        return Chapter.builder()
                .id(UUID.randomUUID().toString())
                .title("Зміст")
                .level(1)
                .content(content.toString())
                .children(new ArrayList<>())
                .paragraphId(paragraphId)
                .build();
    }

    private Chapter processSection(Element section, int level, Counter counter) {
        String title = "Розділ";
        Element titleEl = section.children().stream()
                .filter(e -> "title".equalsIgnoreCase(e.tagName()))
                .findFirst()
                .orElse(null);

        if (titleEl != null && !titleEl.text().isBlank()) {
            title = titleEl.text().trim();
        }

        StringBuilder content = new StringBuilder();
        List<Chapter> children = new ArrayList<>();

        for (Element child : section.children()) {
            String tag = child.tagName().toLowerCase(Locale.ROOT);

            if ("section".equals(tag)) {
                Chapter subChapter = processSection(child, level + 1, counter);
                if (subChapter != null) {
                    children.add(subChapter);
                }
            } else if (!"title".equals(tag)) {
                processElement(child, tag, content, counter, level);
            }
        }

        if (content.isEmpty() && children.isEmpty()) {
            return null;
        }

        // Шукаємо перший paragraphId у контенті
        String paragraphId = findFirstParagraphId(content);

        return Chapter.builder()
                .id(UUID.randomUUID().toString())
                .title(title)
                .level(Math.min(level, 6))
                .content(content.toString())
                .children(children)
                .paragraphId(paragraphId)
                .build();
    }

    /**
     * Знаходить перший paragraphId у контенті за допомогою регулярного виразу.
     */
    private String findFirstParagraphId(StringBuilder content) {
        if (content == null || content.length() == 0) {
            return null;
        }
        String text = content.toString();
        var matcher = PARAGRAPH_ID_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private void processElement(Element element, String tag, StringBuilder content, Counter counter, int level) {
        switch (tag) {
            case "p" -> processParagraph(element, content, counter);
            case "title" -> processTitle(element, content, level);
            case "subtitle" -> processSubtitle(element, content);
            case "epigraph" -> processEpigraph(element, content);
            case "cite" -> processCite(element, content);
            case "poem" -> processPoem(element, content);
            case "text-author" -> processTextAuthor(element, content);
            case "empty-line" -> content.append("<br/>\n");
            case "emphasis" -> processInline(element, "em", content);
            case "strong" -> processInline(element, "strong", content);
            case "code" -> processInline(element, "code", content);
            case "sub" -> processInline(element, "sub", content);
            case "sup" -> processInline(element, "sup", content);
            case "strikethrough" -> processInline(element, "s", content);
            case "image" -> processImage(element, content);
            default -> {
                String text = element.text();
                if (text != null && !text.trim().isEmpty()) {
                    content.append("<p>").append(escapeHtml(text.trim())).append("</p>\n");
                }
            }
        }
    }

    private void processParagraph(Element element, StringBuilder content, Counter counter) {
        String html = processElementContent(element);
        if (html != null && !html.trim().isEmpty()) {
            String pId = "p" + (++counter.value);
            content.append("<p data-paragraph-id=\"").append(pId).append("\">")
                    .append(html)
                    .append("</p>\n");
        }
    }

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

    private void processEpigraph(Element element, StringBuilder content) {
        content.append("<div class=\"epigraph\">\n");
        for (Element child : element.children()) {
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

    private void processCite(Element element, StringBuilder content) {
        String html = processElementContent(element);
        if (html != null && !html.trim().isEmpty()) {
            content.append("<blockquote>")
                    .append(html)
                    .append("</blockquote>\n");
        }
    }

    private void processPoem(Element element, StringBuilder content) {
        content.append("<div class=\"poem\">\n");
        for (Element child : element.children()) {
            String tag = child.tagName().toLowerCase();
            if ("stanza".equals(tag)) {
                content.append("<div class=\"stanza\">\n");
                for (Element verse : child.children()) {
                    String vTag = verse.tagName().toLowerCase();
                    if ("v".equals(vTag)) {
                        String text = verse.html();
                        if (text != null && !text.trim().isEmpty()) {
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

    private void processTextAuthor(Element element, StringBuilder content) {
        String text = element.text();
        if (text != null && !text.trim().isEmpty()) {
            content.append("<div class=\"text-author\">")
                    .append(escapeHtml(text.trim()))
                    .append("</div>\n");
        }
    }

    private void processInline(Element element, String htmlTag, StringBuilder content) {
        String html = processElementContent(element);
        if (html != null && !html.trim().isEmpty()) {
            content.append("<").append(htmlTag).append(">")
                    .append(html)
                    .append("</").append(htmlTag).append(">");
        }
    }

    private void processImage(Element element, StringBuilder content) {
        String href = element.attr("href");
        if (href == null || href.isEmpty()) {
            href = element.attr("xlink:href");
        }
        if (href == null || href.isEmpty()) {
            href = element.attr("src");
        }

        if (href != null && href.startsWith("#")) {
            String imageId = href.substring(1);
            content.append("<img data-image-id=\"").append(escapeHtml(imageId))
                    .append("\" src=\"data:image/jpeg;base64,PLACEHOLDER\" alt=\"Зображення\"/>");
        } else if (href != null && !href.isEmpty()) {
            content.append("<img src=\"").append(escapeHtml(href))
                    .append("\" alt=\"Зображення\"/>");
        }
    }

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

                String innerHtml = processElementContent(child);
                if (innerHtml != null && !innerHtml.trim().isEmpty()) {
                    result.append("<").append(htmlTag).append(">")
                            .append(innerHtml)
                            .append("</").append(htmlTag).append(">");
                } else {
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