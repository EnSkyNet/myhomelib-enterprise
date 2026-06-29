package com.myhomelibcorp.infrastructure.parser.fb2;

public class Fb2LanguageParser {

    public void parse(String elementName, String text, Fb2ParserContext context) {
        if (context.isInTitleInfo() && "lang".equals(elementName)) {
            context.setLanguage(text.trim().toLowerCase());
        }
    }
}