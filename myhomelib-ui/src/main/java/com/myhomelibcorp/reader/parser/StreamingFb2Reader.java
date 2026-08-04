package com.myhomelibcorp.reader.parser;

import com.myhomelibcorp.reader.model.Chapter;
import lombok.extern.slf4j.Slf4j;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Потоковий читач FB2 для великих книг.
 * Не завантажує весь файл у пам'ять, а читає розділ за розділом.
 * Підтримує різні структури FB2.
 */
@Slf4j
public class StreamingFb2Reader {

    private final XMLInputFactory xmlFactory = XMLInputFactory.newInstance();

    public StreamingFb2Reader() {
        xmlFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xmlFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        xmlFactory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        xmlFactory.setProperty(XMLInputFactory.IS_COALESCING, false);
    }

    /**
     * Читає книгу та віддає розділи через callback.
     * Не завантажує весь файл у пам'ять.
     */
    public void readChapters(InputStream inputStream, Consumer<Chapter> chapterConsumer) throws Exception {
        XMLStreamReader reader = xmlFactory.createXMLStreamReader(inputStream);

        boolean inBody = false;
        boolean inSection = false;
        boolean inTitle = false;
        StringBuilder sectionContent = new StringBuilder();
        StringBuilder titleBuilder = new StringBuilder();
        String sectionTitle = "Без заголовка";
        int paragraphCounter = 0;

        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String localName = reader.getLocalName();

                if ("body".equalsIgnoreCase(localName)) {
                    inBody = true;
                    log.debug("Знайдено body");
                }

                if (inBody && "section".equalsIgnoreCase(localName)) {
                    inSection = true;
                    sectionContent = new StringBuilder();
                    titleBuilder = new StringBuilder();
                    sectionTitle = "Без заголовка";
                    log.debug("Знайдено нову секцію");
                }

                if (inSection && "title".equalsIgnoreCase(localName)) {
                    inTitle = true;
                    log.debug("Знайдено title в секції");
                }

                if (inSection && inTitle) {
                    // Збираємо текст заголовка з усіх дочірніх елементів
                    String titleText = readElementText(reader, true);
                    if (titleText != null && !titleText.isEmpty()) {
                        titleBuilder.append(titleText);
                    }
                }

                if (inSection && !inTitle) {
                    // Обробка контенту
                    if ("p".equalsIgnoreCase(localName) || "epigraph".equalsIgnoreCase(localName)) {
                        String text = readElementText(reader, true);
                        if (text != null && !text.isEmpty()) {
                            String pId = "p" + (++paragraphCounter);
                            sectionContent.append("<p data-paragraph-id=\"").append(pId).append("\">")
                                    .append(text)
                                    .append("</p>\n");
                            log.debug("Додано параграф {}: {}", pId, text.substring(0, Math.min(50, text.length())));
                        }
                    } else if ("subtitle".equalsIgnoreCase(localName)) {
                        String text = readElementText(reader, true);
                        if (text != null && !text.isEmpty()) {
                            sectionContent.append("<h3>").append(text).append("</h3>\n");
                        }
                    } else if ("poem".equalsIgnoreCase(localName)) {
                        String text = readElementText(reader, true);
                        if (text != null && !text.isEmpty()) {
                            sectionContent.append("<div class=\"poem\">").append(text).append("</div>\n");
                        }
                    } else if ("cite".equalsIgnoreCase(localName)) {
                        String text = readElementText(reader, true);
                        if (text != null && !text.isEmpty()) {
                            sectionContent.append("<blockquote>").append(text).append("</blockquote>\n");
                        }
                    } else if ("empty-line".equalsIgnoreCase(localName)) {
                        sectionContent.append("<br/>\n");
                    }
                }
            }

            if (event == XMLStreamConstants.END_ELEMENT) {
                String localName = reader.getLocalName();

                if (inSection && "title".equalsIgnoreCase(localName)) {
                    inTitle = false;
                    String title = titleBuilder.toString().trim();
                    if (!title.isEmpty()) {
                        sectionTitle = title;
                    }
                    log.debug("Заголовок секції: {}", sectionTitle);
                }

                if (inBody && "section".equalsIgnoreCase(localName)) {
                    // Створюємо Chapter і віддаємо
                    if (sectionContent.length() > 0 || !sectionTitle.isEmpty()) {
                        Chapter chapter = Chapter.builder()
                                .id(UUID.randomUUID().toString())
                                .title(sectionTitle)
                                .level(1)
                                .content(sectionContent.toString())
                                .children(new ArrayList<>())
                                .build();
                        chapterConsumer.accept(chapter);
                        log.debug("Секцію завершено: {}, параграфів: {}", sectionTitle, paragraphCounter);
                    }

                    inSection = false;
                }

                if ("body".equalsIgnoreCase(localName)) {
                    inBody = false;
                    break;
                }
            }
        }

        reader.close();
        log.info("Streaming FB2 read completed, {} paragraphs processed", paragraphCounter);
    }

    /**
     * Читає текст елемента, включно з вкладеними тегами.
     * Повертає HTML-представлення вмісту.
     */
    private String readElementText(XMLStreamReader reader, boolean collectNested) throws Exception {
        StringBuilder text = new StringBuilder();
        int depth = 1;

        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                String localName = reader.getLocalName();

                if (collectNested) {
                    // Обробляємо вкладені теги
                    if ("strong".equalsIgnoreCase(localName) || "b".equalsIgnoreCase(localName)) {
                        String childText = readElementText(reader, true);
                        text.append("<b>").append(childText).append("</b>");
                    } else if ("emphasis".equalsIgnoreCase(localName) || "i".equalsIgnoreCase(localName)) {
                        String childText = readElementText(reader, true);
                        text.append("<i>").append(childText).append("</i>");
                    } else if ("strikethrough".equalsIgnoreCase(localName) || "s".equalsIgnoreCase(localName)) {
                        String childText = readElementText(reader, true);
                        text.append("<s>").append(childText).append("</s>");
                    } else if ("p".equalsIgnoreCase(localName)) {
                        String childText = readElementText(reader, true);
                        text.append("<p>").append(childText).append("</p>");
                    } else if ("subtitle".equalsIgnoreCase(localName)) {
                        String childText = readElementText(reader, true);
                        text.append("<h3>").append(childText).append("</h3>");
                    } else if ("code".equalsIgnoreCase(localName)) {
                        String childText = readElementText(reader, true);
                        text.append("<code>").append(childText).append("</code>");
                    } else if ("sup".equalsIgnoreCase(localName)) {
                        String childText = readElementText(reader, true);
                        text.append("<sup>").append(childText).append("</sup>");
                    } else if ("sub".equalsIgnoreCase(localName)) {
                        String childText = readElementText(reader, true);
                        text.append("<sub>").append(childText).append("</sub>");
                    } else {
                        // Невідомий тег - просто пропускаємо
                        String childText = readElementText(reader, true);
                        text.append(childText);
                    }
                }
            } else if (event == XMLStreamConstants.CHARACTERS) {
                String chars = reader.getText();
                if (chars != null && !chars.isBlank()) {
                    text.append(escapeHtml(chars.trim()));
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
        }

        return text.toString();
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