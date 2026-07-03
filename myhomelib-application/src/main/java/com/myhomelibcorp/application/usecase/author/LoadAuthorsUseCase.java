package com.myhomelibcorp.application.usecase.author;

import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.domain.model.author.Author;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LoadAuthorsUseCase {
    private final AuthorRepository authorRepository;

    public List<Author> execute() {
        return authorRepository.findAll();
    }
}