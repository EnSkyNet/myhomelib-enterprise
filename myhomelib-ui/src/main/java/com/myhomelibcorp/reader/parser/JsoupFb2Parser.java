package com.myhomelibcorp.reader.parser;

import com.myhomelibcorp.reader.model.BookDocument;
import com.myhomelibcorp.reader.model.BookMetadata;
import com.myhomelibcorp.reader.model.Chapter;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
public class JsoupFb2Parser {

    // Список кодувань для спроби
    private static final Charset[] CHARSETS = {
            Charset.forName("UTF-8"),
            Charset.forName("Windows-1251"),
            Charset.forName("IBM866"),
            Charset.forName("KOI8-R"),
            Charset.forName("ISO-8859-5")
    };

    public BookDocument parse(InputStream inputStream) throws Exception {
        long startTime = System.currentTimeMillis();

        // Читаємо весь вміст як byte[]
        byte[] data = inputStream.readAllBytes();

        // Пробуємо різні кодування
        String content = null;
        Charset usedCharset = null;

        for (Charset charset : CHARSETS) {
            try {
                String testContent = new String(data, charset);
                // Перевіряємо, чи є ознаки правильного кодування
                if (isValidContent(testContent)) {
                    content = testContent;
                    usedCharset = charset;
                    log.info("✅ Знайдено правильне кодування: {}", charset);
                    break;
                }
            } catch (Exception e) {
                // Ігноруємо
            }
        }

        // Якщо жодне кодування не підійшло - використовуємо UTF-8
        if (content == null) {
            content = new String(data, StandardCharsets.UTF_8);
            usedCharset = StandardCharsets.UTF_8;
            log.warn("⚠️ Не вдалося визначити кодування, використовуємо UTF-8");
        }

        log.info("Парсинг FB2 з кодуванням: {}", usedCharset);

        // Парсимо за допомогою JSoup
        Document doc = Jsoup.parse(content, "", org.jsoup.parser.Parser.xmlParser());

        log.info("Кореневий елемент: {}", doc.select("FictionBook").first() != null ? "FictionBook" : "unknown");

        BookMetadata metadata = extractMetadata(doc);
        List<Chapter> chapters = extractChapters(doc);

        BookDocument document = BookDocument.builder()
                .metadata(metadata)
                .chapters(chapters)
                .footnotes(new ArrayList<>())
                .images(new ArrayList<>())
                .build();

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("FB2 parsed: title='{}', authors={}, chapters={} ({} ms)",
                metadata.getTitle(),
                metadata.getAuthors(),
                chapters.size(),
                elapsed);

        return document;
    }

    /**
     * Перевіряє, чи текст виглядає як правильно закодований
     */
    private boolean isValidContent(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        // Перевіряємо наявність ключових XML тегів
        boolean hasFictionBook = text.contains("FictionBook") || text.contains("fictionbook");
        boolean hasTitle = text.contains("book-title") || text.contains("book-title");

        // Перевіряємо відсутність кракозябр (символів заміни)
        boolean hasReplacementChar = text.contains("\uFFFD");

        // Перевіряємо наявність кирилиці
        boolean hasCyrillic = text.matches(".*[\\u0400-\\u04FF].*");

        // Якщо є ключові теги і немає символів заміни - кодування правильне
        if (hasFictionBook && !hasReplacementChar) {
            return true;
        }

        // Якщо є кирилиця і ключові теги - теж добре
        if (hasCyrillic && (hasFictionBook || hasTitle)) {
            return true;
        }

        return false;
    }

    private BookMetadata extractMetadata(Document doc) {
        BookMetadata.BookMetadataBuilder builder = BookMetadata.builder();

        Element titleInfo = doc.select("description > title-info, title-info").first();

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
            builder.authors(new ArrayList<>());
            builder.language("uk");
        }

        Element publishInfo = doc.select("description > publish-info, publish-info").first();
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

    private List<Chapter> extractChapters(Document doc) {
        List<Chapter> chapters = new ArrayList<>();

        Element body = doc.select("body").first();

        if (body == null) {
            log.warn("Не знайдено body");
            String text = doc.text();
            if (text != null && !text.trim().isEmpty()) {
                Chapter fallback = Chapter.builder()
                        .id(UUID.randomUUID().toString())
                        .title("Текст")
                        .level(1)
                        .content("<p>" + escapeHtml(text.trim()) + "</p>")
                        .build();
                chapters.add(fallback);
            }
            return chapters;
        }

        Elements sections = body.children().select("section");
        int paragraphCounter = 0;

        if (sections.isEmpty()) {
            Chapter singleChapter = processDirectBody(body, new Counter(paragraphCounter));
            if (singleChapter != null && singleChapter.getContent() != null && !singleChapter.getContent().isEmpty()) {
                chapters.add(singleChapter);
            }
        } else {
            for (Element section : sections) {
                Counter counter = new Counter(paragraphCounter);
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
                    .title("Текст")
                    .level(1)
                    .content("<p>Немає тексту для відображення.</p>")
                    .build();
            chapters.add(defaultChapter);
            log.warn("Створено заглушку-замінник");
        }

        log.info("Знайдено секцій: {}, параграфів: {}", chapters.size(), paragraphCounter);
        return chapters;
    }

    private Chapter processDirectBody(Element body, Counter counter) {
        StringBuilder content = new StringBuilder();
        Elements children = body.children();

        for (Element child : children) {
            String tag = child.tagName().toLowerCase();

            if ("p".equals(tag)) {
                String text = child.text();
                if (text != null && !text.trim().isEmpty()) {
                    String pId = "p" + (++counter.value);
                    content.append("<p data-paragraph-id=\"").append(pId).append("\">")
                            .append(escapeHtml(text.trim()))
                            .append("</p>\n");
                }
            } else if ("title".equals(tag)) {
                String title = child.text();
                if (title != null && !title.trim().isEmpty()) {
                    content.append("<h1>").append(escapeHtml(title.trim())).append("</h1>\n");
                }
            } else if ("subtitle".equals(tag)) {
                String sub = child.text();
                if (sub != null && !sub.trim().isEmpty()) {
                    content.append("<h2>").append(escapeHtml(sub.trim())).append("</h2>\n");
                }
            } else if ("epigraph".equals(tag) || "cite".equals(tag)) {
                String text = child.text();
                if (text != null && !text.trim().isEmpty()) {
                    content.append("<blockquote>").append(escapeHtml(text.trim())).append("</blockquote>\n");
                }
            } else if ("poem".equals(tag)) {
                String text = child.text();
                if (text != null && !text.trim().isEmpty()) {
                    content.append("<div class=\"poem\">").append(escapeHtml(text.trim())).append("</div>\n");
                }
            } else if ("empty-line".equals(tag)) {
                content.append("<br/>\n");
            } else {
                String text = child.text();
                if (text != null && !text.trim().isEmpty()) {
                    content.append("<p>").append(escapeHtml(text.trim())).append("</p>\n");
                }
            }
        }

        if (content.length() == 0) {
            return null;
        }

        return Chapter.builder()
                .id(UUID.randomUUID().toString())
                .title("Текст")
                .level(1)
                .content(content.toString())
                .children(new ArrayList<>())
                .build();
    }

    private Chapter processSection(Element section, int level, Counter counter) {
        String title = "Без заголовка";
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

            switch (tag) {
                case "p": {
                    String text = child.text();
                    if (text != null && !text.trim().isEmpty()) {
                        String pId = "p" + (++counter.value);
                        content.append("<p data-paragraph-id=\"").append(pId).append("\">")
                                .append(escapeHtml(text.trim()))
                                .append("</p>\n");
                    }
                    break;
                }
                case "title": {
                    String t = child.text();
                    if (t != null && !t.trim().isEmpty()) {
                        content.append("<h2>").append(escapeHtml(t.trim())).append("</h2>\n");
                    }
                    break;
                }
                case "subtitle": {
                    String sub = child.text();
                    if (sub != null && !sub.trim().isEmpty()) {
                        content.append("<h3>").append(escapeHtml(sub.trim())).append("</h3>\n");
                    }
                    break;
                }
                case "epigraph":
                case "cite": {
                    String text = child.text();
                    if (text != null && !text.trim().isEmpty()) {
                        content.append("<blockquote>").append(escapeHtml(text.trim())).append("</blockquote>\n");
                    }
                    break;
                }
                case "poem": {
                    String text = child.text();
                    if (text != null && !text.trim().isEmpty()) {
                        content.append("<div class=\"poem\">").append(escapeHtml(text.trim())).append("</div>\n");
                    }
                    break;
                }
                case "empty-line": {
                    content.append("<br/>\n");
                    break;
                }
                case "section": {
                    Chapter subChapter = processSection(child, level + 1, counter);
                    if (subChapter != null && subChapter.getContent() != null && !subChapter.getContent().isEmpty()) {
                        content.append(subChapter.getContent());
                    }
                    break;
                }
                default: {
                    String text = child.text();
                    if (text != null && !text.trim().isEmpty()) {
                        content.append("<p>").append(escapeHtml(text.trim())).append("</p>\n");
                    }
                    break;
                }
            }
        }

        if (content.length() == 0) {
            return null;
        }

        return Chapter.builder()
                .id(UUID.randomUUID().toString())
                .title(title)
                .level(level)
                .content(content.toString())
                .children(new ArrayList<>())
                .build();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("\n", "<br/>");
    }

    private static class Counter {
        int value;
        Counter(int value) { this.value = value; }
    }
}