package com.myhomelibcorp.infrastructure.parser.fb2;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import lombok.extern.slf4j.Slf4j;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class Fb2AuthorParser {

    public List<Author> parse(XMLStreamReader reader) throws Exception {
        List<Author> authors = new ArrayList<>();
        String firstName = "", middleName = "", lastName = "";
        boolean inAuthor = false;
        boolean inTitleInfo = false;

        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String localName = reader.getLocalName();

                if ("title-info".equalsIgnoreCase(localName)) {
                    inTitleInfo = true;
                }

                if (inTitleInfo && "author".equalsIgnoreCase(localName)) {
                    inAuthor = true;
                    firstName = middleName = lastName = "";
                }

                if (inAuthor) {
                    if ("first-name".equalsIgnoreCase(localName)) {
                        firstName = reader.getElementText().trim();
                    } else if ("middle-name".equalsIgnoreCase(localName)) {
                        middleName = reader.getElementText().trim();
                    } else if ("last-name".equalsIgnoreCase(localName)) {
                        lastName = reader.getElementText().trim();
                    }
                }
            }

            if (event == XMLStreamConstants.END_ELEMENT) {
                String localName = reader.getLocalName();

                if (inTitleInfo && "author".equalsIgnoreCase(localName) && inAuthor) {
                    if (!lastName.isEmpty() || !firstName.isEmpty()) {
                        authors.add(new Author(AuthorId.generate(), firstName, middleName, lastName));
                    }
                    inAuthor = false;
                }

                if ("title-info".equalsIgnoreCase(localName)) {
                    inTitleInfo = false;
                    break;
                }
            }
        }

        return authors;
    }
}