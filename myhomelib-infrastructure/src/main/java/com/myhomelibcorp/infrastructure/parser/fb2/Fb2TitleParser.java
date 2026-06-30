package com.myhomelibcorp.infrastructure.parser.fb2;

import lombok.extern.slf4j.Slf4j;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

@Slf4j
public class Fb2TitleParser {

    public String parse(XMLStreamReader reader) throws Exception {
        boolean inTitleInfo = false;

        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String localName = reader.getLocalName();

                if ("title-info".equalsIgnoreCase(localName)) {
                    inTitleInfo = true;
                }

                if (inTitleInfo && "book-title".equalsIgnoreCase(localName)) {
                    String title = reader.getElementText().trim();
                    log.debug("Знайдено назву: '{}'", title);
                    return title;
                }
            }

            if (event == XMLStreamConstants.END_ELEMENT) {
                if ("title-info".equalsIgnoreCase(reader.getLocalName())) {
                    inTitleInfo = false;
                }
            }
        }

        log.debug("Назву не знайдено, використовуємо 'Без назви'");
        return "Без назви";
    }
}