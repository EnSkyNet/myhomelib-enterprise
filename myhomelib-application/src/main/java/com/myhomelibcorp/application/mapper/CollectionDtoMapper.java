package com.myhomelibcorp.application.mapper;

import com.myhomelibcorp.application.dto.CollectionDto;
import com.myhomelibcorp.domain.model.collection.Collection;

import java.util.Objects;

/** Single mapping policy from the collection domain model to UI/application collection summary DTO. */
public final class CollectionDtoMapper {
    private CollectionDtoMapper() { }

    public static CollectionDto toDto(Collection collection, boolean active, boolean allowDelete) {
        Objects.requireNonNull(collection, "collection");
        return CollectionDto.builder()
                .id(collection.getId())
                .name(collection.getName())
                .active(active)
                .allowRename(true)
                .allowDelete(allowDelete && !active)
                .rootFolder(collection.getRootFolder() == null ? null : collection.getRootFolder().toString())
                .dbFile(collection.getDbFile())
                .type(collection.getType())
                .build();
    }
}
