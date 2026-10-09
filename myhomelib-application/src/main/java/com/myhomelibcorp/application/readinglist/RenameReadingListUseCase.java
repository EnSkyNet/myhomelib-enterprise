package com.myhomelibcorp.application.readinglist;

import com.myhomelibcorp.application.port.out.repository.ReadingListRepository;

import com.myhomelibcorp.domain.model.readinglist.ReadingList;
import com.myhomelibcorp.domain.model.readinglist.ReadingListId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class RenameReadingListUseCase {
    private final ReadingListRepository repository;
    private final ReadingListMutationExecutor mutation;
    public ReadingList execute(String id, String newName, String expectedCollectionId) { return execute(ReadingListId.fromString(id), newName, expectedCollectionId); }
    public ReadingList execute(ReadingListId id, String newName, String expectedCollectionId) {
        String normalized = newName == null ? "" : newName.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("Назва списку читання не може бути порожньою");
        return mutation.execute(expectedCollectionId, () -> {
            ReadingList current = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Список читання не знайдено"));
            repository.findByName(normalized).filter(other -> !other.id().equals(id)).ifPresent(other -> {
                throw new IllegalArgumentException("Список читання з такою назвою вже існує");
            });
            return repository.save(current.rename(normalized));
        });
    }
}
