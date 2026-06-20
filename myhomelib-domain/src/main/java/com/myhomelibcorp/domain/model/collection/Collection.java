package com.myhomelibcorp.domain.model.collection;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.nio.file.Path;

/**
 * Модель колекції книг (відповідає Collection з Delphi).
 */
@Getter
@RequiredArgsConstructor
public class Collection {
    private final String id;
    private final String name;
    private final Path rootFolder;
    private final String dbFile;
    private final int type;
    private final String user;
    private final String password;
    private final String url;
    private final String notes;

    public Collection(String name, Path rootFolder) {
        this.id = java.util.UUID.randomUUID().toString();
        this.name = name;
        this.rootFolder = rootFolder;
        this.dbFile = null;
        this.type = 0;
        this.user = null;
        this.password = null;
        this.url = null;
        this.notes = null;
    }
}