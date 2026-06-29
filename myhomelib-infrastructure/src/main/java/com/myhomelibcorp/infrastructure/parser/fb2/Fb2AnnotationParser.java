package com.myhomelibcorp.infrastructure.parser.fb2;

public class Fb2AnnotationParser {

    public void parse(String elementName, String text, Fb2ParserContext context) {
        if ("annotation".equals(elementName) && context.isInTitleInfo()) {
            context.setInAnnotation(true);
        }
        if (context.isInAnnotation()) {
            context.getAnnotation().append(text);
        }
    }

    public void finishAnnotation(Fb2ParserContext context) {
        context.setInAnnotation(false);
    }
}