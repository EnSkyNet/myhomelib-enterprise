package com.myhomelibcorp.application.readinglist;

import com.myhomelibcorp.application.port.out.repository.ReadingListRepository;

import com.myhomelibcorp.domain.model.readinglist.ReadingList;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class CreateReadingListUseCase {
    private final ReadingListRepository repository;
    private final ReadingListMutationExecutor mutation;
    public ReadingList execute(String name, String description, String expectedCollectionId) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("Назва списку читання не може бути порожньою");
        return mutation.execute(expectedCollectionId, () -> {
            if (repository.findByName(normalized).isPresent()) throw new IllegalArgumentException("Список читання з такою назвою вже існує");
            return repository.save(ReadingList.create(normalized, description));
        });
    }
    public ReadingList execute(String name, String description) { return execute(name, description, null); }
}
