package com.myhomelibcorp.infrastructure.parser.fb2;

import com.myhomelibcorp.domain.model.genre.Genre;
import lombok.extern.slf4j.Slf4j;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class Fb2GenreParser {

    public List<Genre> parse(XMLStreamReader reader) throws Exception {
        List<Genre> genres = new ArrayList<>();
        boolean inTitleInfo = false;

        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String localName = reader.getLocalName();

                if ("title-info".equalsIgnoreCase(localName)) {
                    inTitleInfo = true;
                }

                if (inTitleInfo && "genre".equalsIgnoreCase(localName)) {
                    String code = reader.getElementText().trim();
                    if (!code.isEmpty()) {
                        genres.add(new Genre(code, code));
                    }
                }
            }

            if (event == XMLStreamConstants.END_ELEMENT) {
                if ("title-info".equalsIgnoreCase(reader.getLocalName())) {
                    inTitleInfo = false;
                    break;
                }
            }
        }

        return genres;
    }
}