package com.myhomelibcorp.infrastructure.parser.fb2;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class Fb2ParserContext {
    private String title = "Без назви";
    private List<Author> authors = new ArrayList<>();
    private List<Genre> genres = new ArrayList<>();
    private String series = "";
    private int sequenceNumber = 0;
    private String language = "ru";
    private String keywords = "";
    private StringBuilder annotation = new StringBuilder();
    private String firstName = "", middleName = "", lastName = "";
    private boolean inTitleInfo = false;
    private boolean inAnnotation = false;
    private boolean inAuthor = false;
    private String currentElement = "";
}