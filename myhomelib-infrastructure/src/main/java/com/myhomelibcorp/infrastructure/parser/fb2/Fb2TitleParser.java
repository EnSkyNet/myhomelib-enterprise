package com.myhomelibcorp.infrastructure.parser.fb2;

public class Fb2TitleParser {

    public void parse(String elementName, String text, Fb2ParserContext context) {
        if (context.isInTitleInfo() && "book-title".equals(elementName)) {
            context.setTitle(text.trim());
        }
    }
}