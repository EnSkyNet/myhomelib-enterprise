package com.myhomelibcorp.domain.model.group;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Модель групи книг (Favorites, To Read тощо).
 */
@Getter
@RequiredArgsConstructor
public class Group {
    private final Long id;
    private final String name;
    private final boolean allowDelete;

    public Group(String name, boolean allowDelete) {
        this.id = null; // Будет присвоєно при збереженні в БД
        this.name = name;
        this.allowDelete = allowDelete;
    }
}