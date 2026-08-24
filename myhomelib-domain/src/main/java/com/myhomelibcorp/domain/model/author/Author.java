package com.myhomelibcorp.domain.model.author;

import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import lombok.Getter;

@Getter
public class Author {
    private final AuthorId id;
    private String firstName;
    private String middleName;
    private String lastName;
    private String annotation;

    public Author(AuthorId id, String firstName, String middleName, String lastName) {
        this(id, firstName, middleName, lastName, "");
    }

    public Author(AuthorId id, String firstName, String middleName, String lastName, String annotation) {
        this.id = id;
        this.firstName = firstName;
        this.middleName = middleName;
        this.lastName = lastName;
        this.annotation = annotation == null ? "" : annotation;
    }

    public Author(String firstName, String middleName, String lastName) {
        this(AuthorId.generate(), firstName, middleName, lastName, "");
    }

    public String getFullName() {
        StringBuilder sb = new StringBuilder();
        if (lastName != null && !lastName.isBlank()) sb.append(lastName);
        if (firstName != null && !firstName.isBlank()) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(firstName);
        }
        if (middleName != null && !middleName.isBlank()) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(middleName);
        }
        return sb.toString();
    }

    public String getShortName() {
        StringBuilder sb = new StringBuilder();
        if (lastName != null && !lastName.isBlank()) sb.append(lastName);
        if (firstName != null && !firstName.isBlank()) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(firstName.charAt(0)).append(".");
        }
        if (middleName != null && !middleName.isBlank()) {
            sb.append(middleName.charAt(0)).append(".");
        }
        return sb.toString();
    }

    public void updateName(String firstName, String middleName, String lastName) {
        this.firstName = firstName;
        this.middleName = middleName;
        this.lastName = lastName;
    }

    public void updateAnnotation(String annotation) {
        this.annotation = annotation == null ? "" : annotation;
    }

    @Override
    public String toString() {
        return getFullName();
    }
}