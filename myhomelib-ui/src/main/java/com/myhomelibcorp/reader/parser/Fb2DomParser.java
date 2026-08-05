package com.myhomelibcorp.reader.parser;

import com.myhomelibcorp.reader.model.BookDocument;
import com.myhomelibcorp.reader.model.BookMetadata;
import com.myhomelibcorp.reader.model.Chapter;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
public class Fb2DomParser {

    private static final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

    static {
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        factory.setIgnoringComments(true);
        factory.setIgnoringElementContentWhitespace(false);

        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (Exception e) {
            log.warn("Could not set XXE protection features", e);
        }
    }

    public BookDocument parse(InputStream inputStream) throws Exception {
        long startTime = System.currentTimeMillis();

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(inputStream));
        Element root = doc.getDocumentElement();

        log.info("Кореневий елемент: {}", root.getTagName());

        BookMetadata metadata = extractMetadata(root);
        List<Chapter> chapters = extractChapters(root);

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

        if (!chapters.isEmpty() && chapters.get(0).getContent() != null) {
            String sample = chapters.get(0).getContent();
            log.debug("Зразок HTML першого розділу (перші 300 символів): {}",
                    sample.substring(0, Math.min(300, sample.length())));
        } else {
            log.warn("Перший розділ не містить контенту!");
        }

        return document;
    }

    private BookMetadata extractMetadata(Element root) {
        BookMetadata.BookMetadataBuilder builder = BookMetadata.builder();

        Element description = findFirstChild(root, "description");
        Element titleInfo = null;
        if (description != null) {
            titleInfo = findFirstChild(description, "title-info");
        }
        if (titleInfo == null) {
            titleInfo = findFirstChild(root, "title-info");
        }

        if (titleInfo != null) {
            log.info("Знайдено title-info");
            String title = getChildText(titleInfo, "book-title");
            if (title == null || title.trim().isEmpty()) {
                Element seq = findFirstChild(titleInfo, "sequence");
                if (seq != null) {
                    String seqName = seq.getAttribute("name");
                    if (seqName != null && !seqName.trim().isEmpty()) {
                        title = seqName;
                    }
                }
                if (title == null || title.trim().isEmpty()) {
                    title = "Без назви";
                }
            }
            builder.title(title.trim());

            List<String> authors = new ArrayList<>();
            NodeList authorNodes = titleInfo.getElementsByTagName("author");
            for (int i = 0; i < authorNodes.getLength(); i++) {
                Element authorEl = (Element) authorNodes.item(i);
                String firstName = getChildText(authorEl, "first-name");
                String middleName = getChildText(authorEl, "middle-name");
                String lastName = getChildText(authorEl, "last-name");
                String fullName = (lastName != null ? lastName : "") + " " +
                        (firstName != null ? firstName : "") +
                        (middleName != null && !middleName.isEmpty() ? " " + middleName : "");
                if (!fullName.trim().isEmpty()) {
                    authors.add(fullName.trim());
                }
            }
            builder.authors(authors);

            List<String> genres = new ArrayList<>();
            NodeList genreNodes = titleInfo.getElementsByTagName("genre");
            for (int i = 0; i < genreNodes.getLength(); i++) {
                String genre = genreNodes.item(i).getTextContent().trim();
                if (!genre.isEmpty()) {
                    genres.add(genre);
                }
            }
            if (!genres.isEmpty()) {
                builder.genre(String.join(", ", genres));
            }

            String lang = getChildText(titleInfo, "lang");
            builder.language(lang != null ? lang.trim() : "uk");

            Element seq = findFirstChild(titleInfo, "sequence");
            if (seq != null) {
                String series = seq.getAttribute("name");
                if (series != null && !series.isEmpty()) {
                    builder.series(series.trim());
                }
                String num = seq.getAttribute("number");
                if (num != null && !num.isEmpty()) {
                    try {
                        builder.sequenceNumber(Integer.parseInt(num.trim()));
                    } catch (NumberFormatException ignored) {}
                }
            }

            String annotation = getChildText(titleInfo, "annotation");
            if (annotation != null && !annotation.trim().isEmpty()) {
                builder.annotation(annotation.trim());
            }
        } else {
            log.warn("Не знайдено title-info");
            builder.title("Без назви");
            builder.authors(new ArrayList<>());
            builder.language("uk");
        }

        Element publishInfo = null;
        if (description != null) {
            publishInfo = findFirstChild(description, "publish-info");
        }
        if (publishInfo == null) {
            publishInfo = findFirstChild(root, "publish-info");
        }
        if (publishInfo != null) {
            String publisher = getChildText(publishInfo, "publisher");
            if (publisher != null && !publisher.isEmpty()) {
                builder.publisher(publisher.trim());
            }
            String year = getChildText(publishInfo, "year");
            if (year != null && !year.isEmpty()) {
                builder.year(year.trim());
            }
        }

        return builder.build();
    }

    private List<Chapter> extractChapters(Element root) {
        List<Chapter> chapters = new ArrayList<>();
        Element body = findFirstChild(root, "body");

        if (body == null) {
            log.warn("Не знайдено body");
            // Спроба знайти будь-який контент
            String fullText = root.getTextContent();
            if (fullText != null && !fullText.trim().isEmpty()) {
                Chapter fallback = Chapter.builder()
                        .id(UUID.randomUUID().toString())
                        .title("Текст")
                        .level(1)
                        .content("<p>" + escapeHtml(fullText.trim()) + "</p>")
                        .build();
                chapters.add(fallback);
            }
            return chapters;
        }

        // Отримуємо всі дочірні елементи body
        NodeList childNodes = body.getChildNodes();
        int paragraphCounter = 0;
        int sectionCount = 0;

        for (int i = 0; i < childNodes.getLength(); i++) {
            Node child = childNodes.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) child;
                String tagName = el.getTagName().toLowerCase();

                if ("section".equals(tagName)) {
                    sectionCount++;
                    Chapter chapter = processSection(el, 1, new MutableInt(paragraphCounter));
                    if (chapter != null) {
                        chapters.add(chapter);
                        // Оновлюємо лічильник параграфів
                        paragraphCounter += countParagraphs(chapter);
                    }
                } else if ("p".equals(tagName)) {
                    // Прямі параграфи в body (без секцій)
                    String text = el.getTextContent();
                    if (text != null && !text.trim().isEmpty()) {
                        String pId = "p" + (++paragraphCounter);
                        String content = "<p data-paragraph-id=\"" + pId + "\">" + escapeHtml(text.trim()) + "</p>\n";
                        Chapter directChapter = Chapter.builder()
                                .id(UUID.randomUUID().toString())
                                .title("Текст")
                                .level(1)
                                .content(content)
                                .children(new ArrayList<>())
                                .build();
                        chapters.add(directChapter);
                    }
                } else if ("title".equals(tagName)) {
                    // Заголовок без секції
                    String title = el.getTextContent().trim();
                    if (!title.isEmpty()) {
                        Chapter titleChapter = Chapter.builder()
                                .id(UUID.randomUUID().toString())
                                .title(title)
                                .level(1)
                                .content("<h1>" + escapeHtml(title) + "</h1>")
                                .children(new ArrayList<>())
                                .build();
                        chapters.add(titleChapter);
                    }
                }
            }
        }

        log.info("Знайдено секцій: {}, параграфів: {}", sectionCount, paragraphCounter);

        // Якщо секцій немає, використовуємо весь текст body
        if (chapters.isEmpty()) {
            String bodyText = body.getTextContent();
            if (bodyText != null && !bodyText.trim().isEmpty()) {
                Chapter single = Chapter.builder()
                        .id(UUID.randomUUID().toString())
                        .title("Текст")
                        .level(1)
                        .content("<p>" + escapeHtml(bodyText.trim()) + "</p>")
                        .build();
                chapters.add(single);
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

        return chapters;
    }

    private int countParagraphs(Chapter chapter) {
        int count = 0;
        if (chapter.getContent() != null) {
            // Рахуємо кількість тегів <p> в контенті
            count += chapter.getContent().split("<p").length - 1;
        }
        for (Chapter child : chapter.getChildren()) {
            count += countParagraphs(child);
        }
        return count;
    }

    private static class MutableInt {
        int value;
        MutableInt(int value) { this.value = value; }
    }

    private Chapter processSection(Element sectionEl, int level, MutableInt counter) {
        String title = getSectionTitle(sectionEl);

        StringBuilder content = new StringBuilder(1024);
        NodeList children = sectionEl.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) child;
            String tag = el.getTagName().toLowerCase();

            switch (tag) {
                case "p": {
                    String pText = getElementText(el);
                    if (pText != null && !pText.trim().isEmpty()) {
                        String pId = "p" + (++counter.value);
                        content.append("<p data-paragraph-id=\"").append(pId).append("\">")
                                .append(pText.trim())
                                .append("</p>\n");
                    }
                    break;
                }
                case "title": {
                    String titleText = getElementText(el);
                    if (titleText != null && !titleText.trim().isEmpty()) {
                        content.append("<h2>").append(titleText.trim()).append("</h2>\n");
                    }
                    break;
                }
                case "subtitle": {
                    String subText = getElementText(el);
                    if (subText != null && !subText.trim().isEmpty()) {
                        content.append("<h3>").append(subText.trim()).append("</h3>\n");
                    }
                    break;
                }
                case "epigraph": {
                    String epigraph = getElementText(el);
                    if (epigraph != null && !epigraph.trim().isEmpty()) {
                        content.append("<blockquote>").append(epigraph.trim()).append("</blockquote>\n");
                    }
                    break;
                }
                case "poem": {
                    String poem = getElementText(el);
                    if (poem != null && !poem.trim().isEmpty()) {
                        content.append("<div class=\"poem\">").append(poem.trim()).append("</div>\n");
                    }
                    break;
                }
                case "cite": {
                    String cite = getElementText(el);
                    if (cite != null && !cite.trim().isEmpty()) {
                        content.append("<blockquote>").append(cite.trim()).append("</blockquote>\n");
                    }
                    break;
                }
                case "empty-line": {
                    content.append("<br/>\n");
                    break;
                }
                case "section": {
                    // Вкладені секції - обробляємо рекурсивно
                    Chapter subChapter = processSection(el, level + 1, counter);
                    if (subChapter != null && subChapter.getContent() != null && !subChapter.getContent().isEmpty()) {
                        content.append(subChapter.getContent());
                    }
                    break;
                }
                default: {
                    // Інші теги - просто беремо текст
                    String text = getElementText(el);
                    if (text != null && !text.trim().isEmpty()) {
                        content.append(text.trim()).append(" ");
                    }
                    break;
                }
            }
        }

        // Якщо контенту немає, повертаємо null
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

    private String getSectionTitle(Element sectionEl) {
        // Шукаємо заголовок в секції
        NodeList children = sectionEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) child;
                if ("title".equalsIgnoreCase(el.getTagName())) {
                    String title = getElementText(el);
                    if (title != null && !title.trim().isEmpty()) {
                        return title.trim();
                    }
                }
            }
        }
        return "Без заголовка";
    }

    private String getElementText(Element el) {
        if (el == null) return "";
        NodeList children = el.getChildNodes();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                text.append(child.getTextContent());
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                // Рекурсивно обробляємо вкладені елементи
                text.append(getElementText((Element) child));
            }
        }
        return text.toString();
    }

    private Element findFirstChild(Element parent, String tagName) {
        if (parent == null) return null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) child;
                if (tagName.equalsIgnoreCase(el.getTagName())) {
                    return el;
                }
            }
        }
        return null;
    }

    private String getChildText(Element parent, String tagName) {
        Element child = findFirstChild(parent, tagName);
        return child != null ? getElementText(child) : null;
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