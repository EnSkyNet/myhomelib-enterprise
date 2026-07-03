package com.myhomelibcorp.infrastructure.parser.fb2;

import javax.xml.stream.XMLStreamReader;

public class Fb2SequenceParser {

    public void parse(XMLStreamReader reader, Fb2ParserContext context) {
        if (context.isInTitleInfo()) {
            String nameAttr = reader.getAttributeValue(null, "name");
            if (nameAttr != null && !nameAttr.isBlank()) {
                context.setSeries(nameAttr.trim());
            }
            String numAttr = reader.getAttributeValue(null, "number");
            if (numAttr != null && !numAttr.isBlank()) {
                try {
                    context.setSequenceNumber(Integer.parseInt(numAttr.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }
}