package com.myhomelibcorp.application.mapper;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.domain.model.author.Author;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AuthorMapper {

    @Mapping(target = "id", expression = "java(author.getId().asString())")
    @Mapping(target = "fullName", expression = "java(author.getFullName())")
    @Mapping(target = "shortName", expression = "java(author.getShortName())")
    AuthorDto toDto(Author author);
}