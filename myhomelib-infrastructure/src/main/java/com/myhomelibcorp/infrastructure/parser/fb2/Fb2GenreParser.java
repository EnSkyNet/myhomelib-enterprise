package com.myhomelibcorp.infrastructure.parser.fb2;

import com.myhomelibcorp.domain.model.genre.Genre;

public class Fb2GenreParser {

    public void parse(String elementName, String text, Fb2ParserContext context) {
        if (context.isInTitleInfo() && "genre".equals(elementName)) {
            String code = text.trim();
            if (!code.isEmpty()) {
                context.getGenres().add(new Genre(code, code));
            }
        }
    }
}