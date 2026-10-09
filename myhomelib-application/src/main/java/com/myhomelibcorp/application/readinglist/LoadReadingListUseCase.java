package com.myhomelibcorp.application.readinglist;

import com.myhomelibcorp.application.port.out.repository.ReadingListRepository;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.domain.model.readinglist.ReadingList;
import com.myhomelibcorp.domain.model.readinglist.ReadingListId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component @RequiredArgsConstructor
public class LoadReadingListUseCase {
    private final ReadingListRepository repository;
    private final BookQueryRepository books;
    private final ReadingProgressRepository progress;
    private final BookMapper mapper;
    private final ReadingListQueryExecutor query;

    public ReadingListDetailsDto execute(String id) { return execute(ReadingListId.fromString(id)); }
    public ReadingListDetailsDto execute(ReadingListId id) {
        return query.execute(() -> {
            ReadingList list = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Список читання не знайдено"));
            var items = repository.findItems(id);
            var ids = items.stream().map(item -> item.bookId()).toList();
            Map<String, BookDto> byId = books.findListItemsByIds(ids).stream().map(mapper::toDto)
                    .collect(Collectors.toMap(BookDto::getId, Function.identity(), (a,b) -> a, LinkedHashMap::new));
            Map<String, Double> percents = progress.findByBookIds(ids.stream().map(v -> v.asString()).toList()).values().stream()
                    .collect(Collectors.toMap(p -> p.getBookId(), p -> p.getPercent(), (a,b) -> b));
            List<ReadingListItemDto> dtoItems = items.stream().map(item -> {
                BookDto b = byId.get(item.bookId().asString());
                if (b == null) return null;
                double effectiveProgress = Math.max(b.getProgress(), percents.getOrDefault(b.getId(), 0.0));
                return new ReadingListItemDto(b.getId(), item.position(), b.getTitle(), b.getAuthorsText(), b.getSeries(),
                        b.getSequenceNumber(), b.isLocal(), effectiveProgress);
            }).filter(Objects::nonNull).toList();
            return new ReadingListDetailsDto(new ReadingListDto(list.id().value(), list.name(), list.description(),
                    dtoItems.size(), list.createdAt(), list.updatedAt()), dtoItems);
        });
    }
}
