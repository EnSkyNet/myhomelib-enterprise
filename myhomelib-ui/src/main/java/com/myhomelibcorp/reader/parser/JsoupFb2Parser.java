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
import java.util.stream.Collectors;

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
        TAG_MAP.put("footnote", "div");
        TAG_MAP.put("note", "div");
        TAG_MAP.put("a", "a");
        TAG_MAP.put("image", "img");
        TAG_MAP.put("poem", "div");
        TAG_MAP.put("stanza", "div");
        TAG_MAP.put("v", "div");
        TAG_MAP.put("date", "span");
        TAG_MAP.put("translator", "span");
        TAG_MAP.put("annotation", "div");
    }

    private static final Pattern PARAGRAPH_ID_PATTERN = Pattern.compile("data-paragraph-id=\"([^\"]+)\"");
    private static final Pattern ANCHOR_ID_PATTERN = Pattern.compile("data-anchor-id=\"([^\"]+)\"");

    private int paragraphCounter = 0;
    private Element rootElement;
    private Element bodyElement;

    // Кеш для генерації стабільних anchorId
    private final Map<String, Integer> sectionAnchorCache = new HashMap<>();
    private String currentSectionId = "";

    // ==================== ОСНОВНИЙ МЕТОД ПАРСИНГУ ====================

    public BookDocument parse(InputStream inputStream) throws Exception {
        long startTime = System.currentTimeMillis();
        paragraphCounter = 0;
        rootElement = null;
        bodyElement = null;
        sectionAnchorCache.clear();
        currentSectionId = "";

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
            log.warn("Кореневий елемент FictionBook не знайдено, використовуємо весь документ");
            root = doc;
        }

        // ЗБЕРІГАЄМО ROOT ДЛЯ ГЕНЕРАЦІЇ XPATH
        this.rootElement = root;

        // ЗБЕРІГАЄМО BODY ДЛЯ XPATH
        Element body = root.select("body").first();
        if (body == null) {
            body = root.select("body").first();
            if (body == null) {
                body = root;
            }
        }
        this.bodyElement = body;

        log.info("📄 rootElement: {}, bodyElement: {}",
                rootElement != null ? rootElement.tagName() : "null",
                bodyElement != null ? bodyElement.tagName() : "null");

        BookMetadata metadata = extractMetadata(root);
        List<ImageData> images = extractImages(root);
        Map<String, String> footnotes = extractFootnotes(root);

        List<Chapter> chapters = extractChapters(root, footnotes);

        int paragraphCount = countParagraphs(chapters);

        BookDocument document = BookDocument.builder()
                .metadata(metadata)
                .chapters(chapters)
                .images(images)
                .build();

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("FB2 розпарсено: title='{}', chapters={}, images={}, footnotes={}, paragraphs={} ({} ms)",
                metadata.getTitle(), chapters.size(), images.size(), footnotes.size(), paragraphCount, elapsed);

        return document;
    }

    // ==================== ПЕРЕВІРКА КОДУВАННЯ ====================

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

    // ==================== МЕТАДАНІ ====================

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

    // ==================== ЗОБРАЖЕННЯ ====================

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
                } catch (Exception e) {
                    log.warn("Не вдалося декодувати зображення id={}: {}", id, e.getMessage());
                }
            }
        }

        return images;
    }

    // ==================== ВИНОСКИ ====================

    private Map<String, String> extractFootnotes(Element root) {
        Map<String, String> footnotes = new LinkedHashMap<>();

        Elements noteSections = root.select("section[type=\"notes\"], section[id*=\"note\"], section");
        for (Element section : noteSections) {
            Elements noteElements = section.select("note, footnote, p[note-id]");
            for (Element note : noteElements) {
                String noteId = note.attr("id");
                if (noteId.isEmpty()) {
                    noteId = note.attr("note-id");
                }
                if (noteId.isEmpty()) {
                    noteId = "note-" + (footnotes.size() + 1);
                }
                String noteText = note.text().trim();
                if (!noteText.isEmpty()) {
                    footnotes.put(noteId, noteText);
                }
            }
        }

        Elements binaryNotes = root.select("binary[id*=\"note\"]");
        for (Element binary : binaryNotes) {
            String id = binary.attr("id");
            String content = binary.text().trim();
            if (!id.isEmpty() && !content.isEmpty() && !footnotes.containsKey(id)) {
                footnotes.put(id, content);
            }
        }

        log.info("Знайдено {} виносок", footnotes.size());
        return footnotes;
    }

    // ==================== ОСНОВНИЙ ПАРСИНГ РОЗДІЛІВ ====================

    private List<Chapter> extractChapters(Element root, Map<String, String> footnotes) {
        List<Chapter> chapters = new ArrayList<>();

        Element body = root.select("body").first();
        if (body == null) {
            log.warn("Body не знайдено");
            return chapters;
        }

        // Діагностика: перевіряємо наявність image тегів у body
        int imageTagCount = 0;
        for (Element el : body.getAllElements()) {
            String tagName = el.tagName().toLowerCase();
            if (tagName.contains("image")) {
                imageTagCount++;
            }
        }
        log.info("🖼️ Total image tags found in body: {}", imageTagCount);

        // Отримуємо ТІЛЬКИ top-level sections
        List<Element> topLevelSections = body.children()
                .stream()
                .filter(element -> "section".equalsIgnoreCase(element.tagName()))
                .collect(Collectors.toList());

        paragraphCounter = 0;

        if (topLevelSections.isEmpty()) {
            Chapter singleChapter = processDirectBody(body, footnotes);
            if (singleChapter != null && singleChapter.getContent() != null && !singleChapter.getContent().isEmpty()) {
                chapters.add(singleChapter);
            }
        } else {
            for (Element section : topLevelSections) {
                // Встановлюємо контекст для секції перед обробкою
                String prevSectionId = currentSectionId;
                currentSectionId = getSectionContext(section);

                Chapter chapter = processSection(section, 1, footnotes);
                if (chapter != null) {
                    chapters.add(chapter);
                }

                // Відновлюємо попередній контекст
                currentSectionId = prevSectionId;
            }
        }

        // Додаємо секцію з виносками в кінці
        if (!footnotes.isEmpty()) {
            Chapter notesChapter = createNotesChapter(footnotes);
            if (notesChapter != null) {
                chapters.add(notesChapter);
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

        log.info("Знайдено розділів: {}", chapters.size());
        return chapters;
    }

    // ==================== ГЕНЕРАЦІЯ СТАБІЛЬНОГО ANCHOR ====================

    /**
     * Генерує стабільний anchorId на основі контексту.
     * Не залежить від порядку парсингу.
     */
    private String generateAnchorId(Element element, String sectionContext) {
        // 1. Якщо елемент має власний id - використовуємо його
        String ownId = element.attr("id");
        if (ownId != null && !ownId.isEmpty()) {
            return "a_" + ownId;
        }

        // 2. Використовуємо контекст section + порядковий номер у цій секції
        String base = sectionContext != null && !sectionContext.isEmpty()
                ? sectionContext
                : "sec_" + (sectionAnchorCache.size() + 1);

        int ordinal = sectionAnchorCache.getOrDefault(base, 0) + 1;
        sectionAnchorCache.put(base, ordinal);

        return "a_" + base + "_" + ordinal;
    }

    /**
     * Генерує стабільний контекст для section.
     * На основі id, назви або хешу вмісту.
     */
    private String getSectionContext(Element section) {
        // Спроба взяти id
        String id = section.attr("id");
        if (id != null && !id.isEmpty()) {
            return "sec_" + id;
        }

        // Спроба взяти назву
        Element titleEl = section.select("title").first();
        if (titleEl != null) {
            String title = titleEl.text().trim();
            if (!title.isEmpty()) {
                // Нормалізуємо для використання в anchor
                String normalized = title.toLowerCase()
                        .replaceAll("[^a-zа-я0-9]", "_")
                        .replaceAll("_+", "_");
                if (normalized.length() > 30) {
                    normalized = normalized.substring(0, 30);
                }
                return "sec_" + normalized;
            }
        }

        // Генеруємо на основі хешу вмісту (перші 100 символів)
        String content = section.text();
        if (content.length() > 100) {
            content = content.substring(0, 100);
        }
        String hash = Integer.toHexString(content.hashCode());
        return "sec_hash_" + hash;
    }

    // ==================== ОБРОБКА СЕКЦІЙ ====================

    private Chapter processSection(Element section, int level, Map<String, String> footnotes) {
        // Зберігаємо поточний контекст
        String prevSectionId = currentSectionId;

        // Встановлюємо контекст для цієї секції
        String sectionContext = getSectionContext(section);
        currentSectionId = sectionContext;

        try {
            String title = "Розділ";
            Element titleEl = section.children()
                    .stream()
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
                    Chapter subChapter = processSection(child, level + 1, footnotes);
                    if (subChapter != null) {
                        children.add(subChapter);
                    }
                } else if ("title".equals(tag)) {
                    // Заголовок - пропускаємо (вже оброблений вище)
                } else if (tag.contains("image")) {
                    processImage(child, content);
                } else {
                    processElement(child, tag, content, level, footnotes);
                }
            }

            if (content.isEmpty() && children.isEmpty()) {
                return null;
            }

            String paragraphId = findFirstParagraphId(content);

            return Chapter.builder()
                    .id(UUID.randomUUID().toString())
                    .title(title)
                    .level(Math.min(level, 6))
                    .content(content.toString())
                    .children(children)
                    .paragraphId(paragraphId)
                    .build();

        } finally {
            // Відновлюємо попередній контекст
            currentSectionId = prevSectionId;
        }
    }

    private Chapter processDirectBody(Element body, Map<String, String> footnotes) {
        // Для прямого body використовуємо контекст за замовчуванням
        String prevSectionId = currentSectionId;
        currentSectionId = "sec_body";

        try {
            StringBuilder content = new StringBuilder();
            Elements children = body.children();

            for (Element child : children) {
                String tag = child.tagName().toLowerCase();
                if ("title".equals(tag)) {
                    // Пропускаємо заголовки на рівні body
                } else if (tag.contains("image")) {
                    processImage(child, content);
                } else {
                    processElement(child, tag, content, 1, footnotes);
                }
            }

            if (content.length() == 0) {
                return null;
            }

            String paragraphId = findFirstParagraphId(content);

            return Chapter.builder()
                    .id(UUID.randomUUID().toString())
                    .title("Зміст")
                    .level(1)
                    .content(content.toString())
                    .children(new ArrayList<>())
                    .paragraphId(paragraphId)
                    .build();

        } finally {
            currentSectionId = prevSectionId;
        }
    }

    // ==================== ОБРОБКА ЕЛЕМЕНТІВ ====================

    private void processElement(Element element, String tag, StringBuilder content, int level, Map<String, String> footnotes) {
        switch (tag) {
            case "p" -> processParagraph(element, content, footnotes);
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
            case "a" -> processLink(element, content, footnotes);
            case "date" -> processDate(element, content);
            case "translator" -> processTranslator(element, content);
            case "annotation" -> processAnnotation(element, content);
            default -> {
                // ===== ВИПРАВЛЕНО: КОЖЕН ТЕКСТОВИЙ БЛОК ОТРИМУЄ ANCHOR =====
                String text = element.text();
                if (text != null && !text.trim().isEmpty()) {
                    String anchorId = generateAnchorId(element, currentSectionId);
                    String pId = "p" + (++paragraphCounter);
                    String xpath = generateXPath(element);

                    content.append("<p data-paragraph-id=\"").append(pId).append("\"")
                            .append(" data-anchor-id=\"").append(anchorId).append("\"")
                            .append(" data-xpath=\"").append(escapeHtml(xpath)).append("\"")
                            .append(" data-paragraph-index=\"").append(paragraphCounter).append("\"")
                            .append(">")
                            .append(escapeHtml(text.trim()))
                            .append("</p>\n");
                }
            }
        }
    }

    // ==================== ОБРОБКА ПАРАГРАФІВ З ANCHOR ====================

    /**
     * ВИПРАВЛЕНО: кожен параграф отримує стабільний anchorId.
     */
    private void processParagraph(Element element, StringBuilder content, Map<String, String> footnotes) {
        String html = processElementContent(element, footnotes);
        if (html != null && !html.trim().isEmpty()) {
            // Генеруємо стабільний anchorId
            String anchorId = generateAnchorId(element, currentSectionId);
            String pId = "p" + (++paragraphCounter);
            String xpath = generateXPath(element);

            content.append("<p data-paragraph-id=\"").append(pId).append("\"")
                    .append(" data-anchor-id=\"").append(anchorId).append("\"")
                    .append(" data-xpath=\"").append(escapeHtml(xpath)).append("\"")
                    .append(" data-paragraph-index=\"").append(paragraphCounter).append("\"")
                    .append(">")
                    .append(html)
                    .append("</p>\n");
        }
    }

    // ==================== ГЕНЕРАЦІЯ XPATH ====================

    private String generateXPath(Element element) {
        if (element == null || rootElement == null || bodyElement == null) {
            log.warn("⚠️ generateXPath: element={}, rootElement={}, bodyElement={}",
                    element != null, rootElement != null, bodyElement != null);
            return "";
        }

        StringBuilder path = new StringBuilder();
        Element current = element;
        int depth = 0;
        int MAX_DEPTH = 20;

        // Шукаємо шлях від body до елемента
        while (current != null && !current.equals(bodyElement) && depth < MAX_DEPTH) {
            String tag = current.tagName().toLowerCase();
            int index = 1;
            Element parent = current.parent();

            if (parent != null) {
                int count = 0;
                for (Element child : parent.children()) {
                    if (child.tagName().equalsIgnoreCase(tag)) {
                        count++;
                        if (child.equals(current)) {
                            index = count;
                            break;
                        }
                    }
                }
            }

            path.insert(0, "/" + tag + "[" + index + "]");
            current = current.parent();
            depth++;
        }

        // Додаємо body
        if (bodyElement != null) {
            path.insert(0, "/body");
        }
        // Додаємо FictionBook
        if (rootElement != null) {
            path.insert(0, "/FictionBook");
        }

        String xpath = path.toString();

        // Якщо XPath занадто довгий, використовуємо хеш
        if (xpath.length() > 200) {
            String hash = Integer.toHexString(xpath.hashCode());
            xpath = "/FictionBook/body/hash[" + hash + "]";
            log.debug("XPath too long ({} chars), using hash: {}", path.length(), xpath);
        }

        return xpath;
    }

    // ==================== ОБРОБКА ЗОБРАЖЕНЬ ====================

    private void processImage(Element element, StringBuilder content) {
        String href = element.attr("href");
        if (href == null || href.isEmpty()) {
            href = element.attr("xlink:href");
        }
        if (href == null || href.isEmpty()) {
            href = element.attr("l:href");
        }
        if (href == null || href.isEmpty()) {
            href = element.attr("src");
        }

        if (href != null && href.startsWith("#")) {
            String imageId = href.substring(1);
            content.append("<img data-image-id=\"")
                    .append(escapeHtml(imageId))
                    .append("\" src=\"data:image/jpeg;base64,PLACEHOLDER\" alt=\"Зображення\"/>");
        } else {
            log.warn("⚠️ Image tag found but no valid href: tag={}, href={}", element.tagName(), href);
        }
    }

    // ==================== ОБРОБКА ПОСИЛАНЬ ====================

    private void processLink(Element element, StringBuilder content, Map<String, String> footnotes) {
        String href = element.attr("href");
        if (href == null || href.isEmpty()) {
            href = element.attr("l:href");
        }

        String text = element.text();

        if (href != null && !href.isEmpty()) {
            if (href.startsWith("#")) {
                String targetId = href.substring(1);
                if (footnotes.containsKey(targetId)) {
                    content.append("<a href=\"#").append(targetId).append("\" class=\"footnote-ref\" data-note-id=\"")
                            .append(targetId).append("\">")
                            .append(escapeHtml(text))
                            .append("</a>");
                    return;
                }
                content.append("<a href=\"").append(escapeHtml(href)).append("\" class=\"internal-link\">")
                        .append(escapeHtml(text))
                        .append("</a>");
                return;
            }
            content.append("<a href=\"").append(escapeHtml(href)).append("\" target=\"_blank\" class=\"external-link\">")
                    .append(escapeHtml(text))
                    .append("</a>");
            return;
        }

        content.append(escapeHtml(text));
    }

    // ==================== ОБРОБКА ВМІСТУ ЕЛЕМЕНТІВ ====================

    private String processElementContent(Element element, Map<String, String> footnotes) {
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

                if ("a".equals(tag)) {
                    processLink(child, result, footnotes);
                } else if (tag.contains("image")) {
                    processImage(child, result);
                } else {
                    String innerHtml = processElementContent(child, footnotes);
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
        }

        return result.toString();
    }

    // ==================== ДОПОМІЖНІ МЕТОДИ ====================

    private String findFirstParagraphId(StringBuilder content) {
        if (content == null || content.length() == 0) {
            return null;
        }
        String text = content.toString();

        // Спочатку шукаємо anchorId
        var matcher = ANCHOR_ID_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }

        // Fallback: шукаємо paragraphId
        var pMatcher = PARAGRAPH_ID_PATTERN.matcher(text);
        if (pMatcher.find()) {
            return pMatcher.group(1);
        }

        return null;
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
        String html = processElementContent(element, new HashMap<>());
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
        String html = processElementContent(element, new HashMap<>());
        if (html != null && !html.trim().isEmpty()) {
            content.append("<").append(htmlTag).append(">")
                    .append(html)
                    .append("</").append(htmlTag).append(">");
        }
    }

    private void processDate(Element element, StringBuilder content) {
        String text = element.text();
        if (text != null && !text.trim().isEmpty()) {
            content.append("<span class=\"date\">")
                    .append(escapeHtml(text.trim()))
                    .append("</span>");
        }
    }

    private void processTranslator(Element element, StringBuilder content) {
        String text = element.text();
        if (text != null && !text.trim().isEmpty()) {
            content.append("<span class=\"translator\">")
                    .append(escapeHtml(text.trim()))
                    .append("</span>");
        }
    }

    private void processAnnotation(Element element, StringBuilder content) {
        String html = processElementContent(element, new HashMap<>());
        if (html != null && !html.trim().isEmpty()) {
            content.append("<div class=\"annotation\">")
                    .append(html)
                    .append("</div>\n");
        }
    }

    // ==================== ВИНОСКИ ====================

    private Chapter createNotesChapter(Map<String, String> footnotes) {
        if (footnotes.isEmpty()) {
            return null;
        }

        StringBuilder content = new StringBuilder();
        content.append("<div class=\"notes-section\">\n");
        content.append("<h2>Примітки</h2>\n");

        int index = 1;
        for (Map.Entry<String, String> entry : footnotes.entrySet()) {
            String id = entry.getKey();
            String text = entry.getValue();
            content.append("<div class=\"footnote\" id=\"").append(id).append("\">");
            content.append("<span class=\"note-number\">").append(index).append(". </span>");
            content.append(escapeHtml(text));
            content.append("</div>\n");
            index++;
        }

        content.append("</div>\n");

        return Chapter.builder()
                .id(UUID.randomUUID().toString())
                .title("Примітки")
                .level(2)
                .content(content.toString())
                .children(new ArrayList<>())
                .paragraphId(null)
                .build();
    }

    // ==================== ДОПОМІЖНІ МЕТОДИ ====================

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
            // Рахуємо параграфи з data-anchor-id або data-paragraph-id
            String content = chapter.getContent();
            int anchorCount = content.split("data-anchor-id=\"").length - 1;
            int pCount = content.split("data-paragraph-id=\"").length - 1;
            count += Math.max(anchorCount, pCount);
        }
        for (Chapter child : chapter.getChildren()) {
            count += countParagraphsInChapter(child);
        }
        return count;
    }
}