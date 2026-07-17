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

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.*;

@Slf4j
public class Fb2DomParser {

    private static final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

    static {
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        factory.setIgnoringComments(true);
        factory.setIgnoringElementContentWhitespace(true);
    }

    public BookDocument parse(InputStream inputStream) throws Exception {
        long startTime = System.currentTimeMillis();

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(inputStream));

        Element root = doc.getDocumentElement();
        log.info("Кореневий елемент: {}", root.getTagName());

        BookDocument.BookDocumentBuilder documentBuilder = BookDocument.builder();
        BookMetadata.BookMetadataBuilder metadataBuilder = BookMetadata.builder();

        // ---- Метадані ----
        Element titleInfo = findFirstChild(root, "title-info");
        if (titleInfo != null) {
            log.info("Знайдено title-info");
            // Заголовок
            String title = getChildText(titleInfo, "book-title");
            if (title == null || title.trim().isEmpty()) {
                // Спробуємо взяти з sequence або інших місць
                Element seq = findFirstChild(titleInfo, "sequence");
                if (seq != null) {
                    String seqName = seq.getAttribute("name");
                    if (seqName != null && !seqName.trim().isEmpty()) {
                        title = seqName;
                    }
                }
                // Якщо все ще порожньо, використовуємо "Без назви"
                if (title == null || title.trim().isEmpty()) {
                    title = "Без назви";
                }
            }
            metadataBuilder.title(title.trim());

            // Автори
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
            metadataBuilder.authors(authors);

            // Жанри
            List<String> genres = new ArrayList<>();
            NodeList genreNodes = titleInfo.getElementsByTagName("genre");
            for (int i = 0; i < genreNodes.getLength(); i++) {
                String genre = genreNodes.item(i).getTextContent().trim();
                if (!genre.isEmpty()) {
                    genres.add(genre);
                }
            }
            if (!genres.isEmpty()) {
                metadataBuilder.genre(String.join(", ", genres));
            }

            // Мова
            String lang = getChildText(titleInfo, "lang");
            metadataBuilder.language(lang != null ? lang.trim() : "uk");

            // Серія
            Element seq = findFirstChild(titleInfo, "sequence");
            if (seq != null) {
                String series = seq.getAttribute("name");
                if (series != null && !series.isEmpty()) {
                    metadataBuilder.series(series.trim());
                }
                String num = seq.getAttribute("number");
                if (num != null && !num.isEmpty()) {
                    try {
                        metadataBuilder.sequenceNumber(Integer.parseInt(num.trim()));
                    } catch (NumberFormatException ignored) {}
                }
            }

            // Анотація
            String annotation = getChildText(titleInfo, "annotation");
            if (annotation != null && !annotation.trim().isEmpty()) {
                metadataBuilder.annotation(annotation.trim());
            }
        } else {
            log.warn("Не знайдено title-info");
            // Спробуємо отримати назву з першого розділу
            Element firstSection = findFirstChild(findFirstChild(root, "body"), "section");
            if (firstSection != null) {
                String titleFromSection = getChildText(firstSection, "title");
                if (titleFromSection != null && !titleFromSection.trim().isEmpty()) {
                    metadataBuilder.title(titleFromSection.trim());
                }
            }
            if (metadataBuilder.build().getTitle() == null) {
                metadataBuilder.title("Без назви");
            }
            metadataBuilder.authors(new ArrayList<>());
            metadataBuilder.language("uk");
        }

        // Видавництво
        Element publishInfo = findFirstChild(root, "publish-info");
        if (publishInfo != null) {
            String publisher = getChildText(publishInfo, "publisher");
            if (publisher != null && !publisher.isEmpty()) {
                metadataBuilder.publisher(publisher.trim());
            }
            String year = getChildText(publishInfo, "year");
            if (year != null && !year.isEmpty()) {
                metadataBuilder.year(year.trim());
            }
        }

        // ---- Основний текст ----
        Element body = findFirstChild(root, "body");
        List<Chapter> chapters = new ArrayList<>();

        if (body != null) {
            NodeList sectionNodes = body.getElementsByTagName("section");
            int count = sectionNodes.getLength();
            log.info("Знайдено секцій: {}", count);

            if (count == 0) {
                String bodyText = body.getTextContent();
                if (bodyText != null && !bodyText.trim().isEmpty()) {
                    Chapter defaultChapter = Chapter.builder()
                            .id(UUID.randomUUID().toString())
                            .title("Текст")
                            .level(1)
                            .content("<p>" + escapeHtml(bodyText.trim()) + "</p>")
                            .build();
                    chapters.add(defaultChapter);
                }
            } else {
                for (int i = 0; i < count; i++) {
                    Element sectionEl = (Element) sectionNodes.item(i);
                    Chapter chapter = processSection(sectionEl, 1);
                    if (chapter != null) {
                        chapters.add(chapter);
                    }
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
            log.warn("Створено розділ-заглушку");
        }

        BookDocument document = documentBuilder
                .metadata(metadataBuilder.build())
                .chapters(chapters)
                .footnotes(new ArrayList<>())
                .images(new ArrayList<>())
                .build();

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("FB2 parsed: title='{}', authors={}, chapters={} ({} ms)",
                document.getMetadata().getTitle(),
                document.getMetadata().getAuthors(),
                document.getChapters().size(),
                elapsed);

        return document;
    }

    private Chapter processSection(Element sectionEl, int level) {
        String title = getChildText(sectionEl, "title");
        if (title != null) {
            title = title.replaceAll("\\s+", " ").trim();
        }
        if (title == null || title.isEmpty()) {
            title = "Розділ";
        }

        StringBuilder content = new StringBuilder(1024);

        NodeList children = sectionEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) child;
            String tag = el.getTagName();

            switch (tag) {
                case "p":
                    String pText = el.getTextContent();
                    if (pText != null && !pText.trim().isEmpty()) {
                        content.append("<p>").append(escapeHtml(pText.trim())).append("</p>\n");
                    }
                    break;
                case "subtitle":
                    String subText = el.getTextContent();
                    if (subText != null && !subText.trim().isEmpty()) {
                        content.append("<h3>").append(escapeHtml(subText.trim())).append("</h3>\n");
                    }
                    break;
                case "epigraph":
                    String epiText = el.getTextContent();
                    if (epiText != null && !epiText.trim().isEmpty()) {
                        content.append("<div class=\"epigraph\">")
                                .append(escapeHtml(epiText.trim()))
                                .append("</div>\n");
                    }
                    break;
                case "poem":
                    String poemText = el.getTextContent();
                    if (poemText != null && !poemText.trim().isEmpty()) {
                        content.append("<div class=\"poem\">")
                                .append(escapeHtml(poemText.trim()))
                                .append("</div>\n");
                    }
                    break;
                case "cite":
                    String citeText = el.getTextContent();
                    if (citeText != null && !citeText.trim().isEmpty()) {
                        content.append("<blockquote>").append(escapeHtml(citeText.trim())).append("</blockquote>\n");
                    }
                    break;
                case "empty-line":
                    content.append("<br/>\n");
                    break;
                case "strong":
                    String strongText = el.getTextContent();
                    if (strongText != null && !strongText.trim().isEmpty()) {
                        content.append("<b>").append(escapeHtml(strongText.trim())).append("</b>");
                    }
                    break;
                case "emphasis":
                    String emphText = el.getTextContent();
                    if (emphText != null && !emphText.trim().isEmpty()) {
                        content.append("<i>").append(escapeHtml(emphText.trim())).append("</i>");
                    }
                    break;
                case "section":
                    Chapter childChapter = processSection(el, level + 1);
                    if (childChapter != null && childChapter.getContent() != null && !childChapter.getContent().isEmpty()) {
                        content.append("<div class=\"subchapter\">")
                                .append("<h4>").append(escapeHtml(childChapter.getTitle())).append("</h4>")
                                .append(childChapter.getContent())
                                .append("</div>\n");
                    }
                    break;
                default:
                    break;
            }
        }

        return Chapter.builder()
                .id(UUID.randomUUID().toString())
                .title(title)
                .level(level)
                .content(content.toString())
                .children(new ArrayList<>())
                .build();
    }

    private Element findFirstChild(Element parent, String tagName) {
        if (parent == null) return null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
                return (Element) child;
            }
        }
        return null;
    }

    private String getChildText(Element parent, String tagName) {
        Element child = findFirstChild(parent, tagName);
        return child != null ? child.getTextContent() : null;
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