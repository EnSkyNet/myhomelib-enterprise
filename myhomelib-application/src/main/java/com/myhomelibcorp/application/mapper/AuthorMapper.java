package com.myhomelibcorp.application.mapper;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.domain.model.author.Author;
import org.springframework.stereotype.Component;

@Component
public class AuthorMapper {

    public AuthorDto toDto(Author author) {
        if (author == null) {
            return null;
        }
        return AuthorDto.builder()
                .id(author.getId().asString())
                .firstName(author.getFirstName())
                .middleName(author.getMiddleName())
                .lastName(author.getLastName())
                .fullName(author.getFullName())
                .shortName(author.getShortName())
                .build();
    }

    public Author toEntity(AuthorDto dto) {
        if (dto == null) {
            return null;
        }
        return new Author(
                com.myhomelibcorp.domain.model.valueobject.AuthorId.fromString(dto.getId()),
                dto.getFirstName(),
                dto.getMiddleName(),
                dto.getLastName()
        );
    }
}