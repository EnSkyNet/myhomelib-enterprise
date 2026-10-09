package com.myhomelibcorp.application.readinglist;

import com.myhomelibcorp.application.port.out.repository.ReadingListRepository;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.readinglist.ReadingListId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component @RequiredArgsConstructor
public class FindReadingListResumeItemUseCase {
    private final ReadingListRepository repository;
    private final BookQueryRepository books;
    private final BookMapper mapper;
    private final ReadingListQueryExecutor query;
    public Optional<BookDto> execute(String id) { return execute(ReadingListId.fromString(id)); }
    public Optional<BookDto> execute(ReadingListId id) {
        return query.execute(() -> repository.findResumeBookId(id).flatMap(books::findById).map(mapper::toDto));
    }
}
