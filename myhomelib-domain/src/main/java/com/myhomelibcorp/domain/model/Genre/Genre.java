package com.myhomelibcorp.domain.model.genre;

import com.myhomelibcorp.domain.model.valueobject.GenreId;
import lombok.Getter;

@Getter
public class Genre {
    private final GenreId id;
    private String name;
    private GenreId parentId;
    private String fb2Code;

    public Genre(GenreId id, String name, GenreId parentId, String fb2Code) {
        this.id = id;
        this.name = name;
        this.parentId = parentId;
        this.fb2Code = fb2Code;
    }

    public Genre(String code, String name) {
        this(new GenreId(code), name, null, code);
    }

    public Genre(String code, String name, String parentCode) {
        this(new GenreId(code), name,
                parentCode != null ? new GenreId(parentCode) : null,
                code);
    }

    public boolean hasParent() {
        return parentId != null;
    }

    @Override
    public String toString() {
        return name + " (" + id.asString() + ")";
    }
}