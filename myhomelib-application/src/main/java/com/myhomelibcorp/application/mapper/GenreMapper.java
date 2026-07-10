package com.myhomelibcorp.application.mapper;

import com.myhomelibcorp.application.dto.GenreDto;
import com.myhomelibcorp.domain.model.genre.Genre;
import org.springframework.stereotype.Component;

@Component
public class GenreMapper {

    public GenreDto toDto(Genre genre) {
        if (genre == null) {
            return null;
        }
        return GenreDto.builder()
                .code(genre.getId().asString())
                .name(genre.getName())
                .parentId(genre.getParentId() != null ? genre.getParentId().asString() : null)
                .fb2Code(genre.getFb2Code())
                .build();
    }

    public Genre toEntity(GenreDto dto) {
        if (dto == null) {
            return null;
        }
        return new Genre(
                com.myhomelibcorp.domain.model.valueobject.GenreId.fromCode(dto.getCode()),
                dto.getName(),
                dto.getParentId() != null ? com.myhomelibcorp.domain.model.valueobject.GenreId.fromCode(dto.getParentId()) : null,
                dto.getFb2Code()
        );
    }
}