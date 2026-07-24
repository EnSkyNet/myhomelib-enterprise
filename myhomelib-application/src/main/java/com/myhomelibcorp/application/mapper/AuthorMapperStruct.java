package com.myhomelibcorp.application.mapper;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.domain.model.author.Author;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

@Mapper(componentModel = "spring")
public interface AuthorMapperStruct {

    AuthorMapperStruct INSTANCE = Mappers.getMapper(AuthorMapperStruct.class);

    @Mapping(target = "id", source = "id.value", qualifiedByName = "uuidToString")
    @Mapping(target = "fullName", expression = "java(author.getFullName())")
    @Mapping(target = "shortName", expression = "java(author.getShortName())")
    AuthorDto toDto(Author author);

    @Named("uuidToString")
    default String uuidToString(UUID uuid) {
        return uuid != null ? uuid.toString() : null;
    }
}