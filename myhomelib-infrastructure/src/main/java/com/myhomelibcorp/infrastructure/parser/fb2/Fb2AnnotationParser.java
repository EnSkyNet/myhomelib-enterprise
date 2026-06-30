package com.myhomelibcorp.infrastructure.parser.fb2;

import lombok.extern.slf4j.Slf4j;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

@Slf4j
public class Fb2AnnotationParser {

    public String parse(XMLStreamReader reader) throws Exception {
        StringBuilder annotation = new StringBuilder();
        boolean inAnnotation = false;
        boolean inTitleInfo = false;

        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String localName = reader.getLocalName();
                if ("title-info".equalsIgnoreCase(localName)) {
                    inTitleInfo = true;
                }
                if (inTitleInfo && "annotation".equalsIgnoreCase(localName)) {
                    inAnnotation = true;
                }
            }

            if (event == XMLStreamConstants.CHARACTERS) {
                if (inAnnotation) {
                    String text = reader.getText();
                    if (text != null && !text.isBlank()) {
                        annotation.append(text);
                    }
                }
            }

            if (event == XMLStreamConstants.END_ELEMENT) {
                String localName = reader.getLocalName();
                if (inTitleInfo && "annotation".equalsIgnoreCase(localName)) {
                    inAnnotation = false;
                }
                if ("title-info".equalsIgnoreCase(localName)) {
                    inTitleInfo = false;
                    break;
                }
            }
        }

        String result = annotation.toString().trim();
        log.debug("Анотація: '{}'", result);
        return result;
    }
}