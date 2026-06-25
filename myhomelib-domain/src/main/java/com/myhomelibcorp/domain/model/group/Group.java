package com.myhomelibcorp.domain.model.group;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class Group {
    private final Long id;
    private final String name;
    private final boolean allowDelete;

    public Group(String name, boolean allowDelete) {
        this.id = null;
        this.name = name;
        this.allowDelete = allowDelete;
    }

    public Group rename(String newName) {
        return new Group(this.id, newName, this.allowDelete);
    }

    @Override
    public String toString() {
        return name;
    }
}