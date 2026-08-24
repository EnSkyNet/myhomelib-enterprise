package com.myhomelibcorp.application.usecase.author;

import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class UpdateAuthorDescriptionUseCase {
    private final AuthorRepository authors;
    public Author execute(AuthorId id,String annotation){
        Author a=authors.findById(id).orElseThrow(()->new IllegalArgumentException("Автор не знайдений"));
        a.updateAnnotation(annotation);return authors.save(a);
    }
}
