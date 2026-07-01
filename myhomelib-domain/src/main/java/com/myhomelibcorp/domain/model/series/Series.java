package com.myhomelibcorp.domain.model.series;

import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import lombok.Getter;

@Getter
public class Series {
    private final SeriesId id;
    private final String name;
    private final String description;

    public Series(SeriesId id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
    }

    public Series(String name) {
        this.id = SeriesId.generate();
        this.name = name;
        this.description = null;
    }

    public Series(String name, String description) {
        this.id = SeriesId.generate();
        this.name = name;
        this.description = description;
    }

    public String getIdAsString() {
        return id.asString();
    }
}