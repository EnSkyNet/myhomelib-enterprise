package com.myhomelibcorp.application.readinglist;

import com.myhomelibcorp.application.port.out.repository.ReadingListRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

@Component @RequiredArgsConstructor
public class ListReadingListsUseCase {
    private final ReadingListRepository repository;
    private final ReadingListQueryExecutor query;
    public List<ReadingListDto> execute() {
        return query.execute(() -> {
            var counts = repository.itemCounts();
            return repository.findAll().stream().map(list -> new ReadingListDto(
                    list.id().value(), list.name(), list.description(), counts.getOrDefault(list.id(), 0),
                    list.createdAt(), list.updatedAt())).toList();
        });
    }
}
