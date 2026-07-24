package com.myhomelibcorp.application.mapper;

import com.myhomelibcorp.application.dto.GenreDto;
import com.myhomelibcorp.domain.model.genre.Genre;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface GenreMapper {

    @Mapping(target = "code", expression = "java(genre.getId().asString())")
    @Mapping(target = "parentId", expression = "java(genre.getParentId() != null ? genre.getParentId().asString() : null)")
    GenreDto toDto(Genre genre);
}