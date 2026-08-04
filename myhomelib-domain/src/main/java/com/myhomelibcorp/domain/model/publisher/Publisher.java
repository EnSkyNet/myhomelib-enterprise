package com.myhomelibcorp.domain.model.publisher;

import com.myhomelibcorp.domain.model.valueobject.PublisherId;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class Publisher {
    private final PublisherId id;
    private final String name;
    private final String description;
    private final String website;
    private final LocalDateTime createdAt;

    public Publisher(PublisherId id, String name, String description, String website, LocalDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.website = website;
        this.createdAt = createdAt;
    }

    public Publisher(String name) {
        this.id = PublisherId.generate();
        this.name = name;
        this.description = null;
        this.website = null;
        this.createdAt = LocalDateTime.now();
    }

    public Publisher(String name, String description, String website) {
        this.id = PublisherId.generate();
        this.name = name;
        this.description = description;
        this.website = website;
        this.createdAt = LocalDateTime.now();
    }

    public Publisher withName(String newName) {
        return new Publisher(this.id, newName, this.description, this.website, this.createdAt);
    }

    public Publisher withDescription(String newDescription) {
        return new Publisher(this.id, this.name, newDescription, this.website, this.createdAt);
    }

    public Publisher withWebsite(String newWebsite) {
        return new Publisher(this.id, this.name, this.description, newWebsite, this.createdAt);
    }

    @Override
    public String toString() {
        return name;
    }
}