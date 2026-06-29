package com.myhomelibcorp.infrastructure.parser.fb2;

public class Fb2KeywordsParser {

    public void parse(String elementName, String text, Fb2ParserContext context) {
        if (context.isInTitleInfo() && "keywords".equals(elementName)) {
            context.setKeywords(text.trim());
        }
    }
}