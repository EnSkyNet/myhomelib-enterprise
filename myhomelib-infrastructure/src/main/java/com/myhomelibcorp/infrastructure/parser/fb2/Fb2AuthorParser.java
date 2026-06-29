package com.myhomelibcorp.infrastructure.parser.fb2;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;

public class Fb2AuthorParser {

    public void parse(String elementName, String text, Fb2ParserContext context) {
        if ("title-info".equals(elementName)) {
            context.setInTitleInfo(true);
        }

        if (context.isInTitleInfo()) {
            if ("author".equals(elementName)) {
                context.setInAuthor(true);
                context.setFirstName("");
                context.setMiddleName("");
                context.setLastName("");
            }

            if (context.isInAuthor()) {
                switch (elementName) {
                    case "first-name" -> context.setFirstName(text.trim());
                    case "middle-name" -> context.setMiddleName(text.trim());
                    case "last-name" -> context.setLastName(text.trim());
                }
            }
        }
    }

    public void finalizeAuthor(Fb2ParserContext context) {
        if (context.isInAuthor()) {
            if (!context.getLastName().isEmpty() || !context.getFirstName().isEmpty()) {
                context.getAuthors().add(new Author(
                        AuthorId.generate(),
                        context.getFirstName(),
                        context.getMiddleName(),
                        context.getLastName()
                ));
            }
            context.setInAuthor(false);
        }
    }
}