package com.myhomelibcorp.application.usecase.author;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.mapper.AuthorMapper;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class LoadAuthorByIdUseCase {

    private final AuthorRepository authorRepository;
    private final AuthorMapper authorMapper;

    public Optional<AuthorDto> execute(AuthorId authorId) {
        return authorRepository.findById(authorId)
                .map(authorMapper::toDto);
    }
}