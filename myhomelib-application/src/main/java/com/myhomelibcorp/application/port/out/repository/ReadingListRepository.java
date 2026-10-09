package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.domain.model.readinglist.ReadingList;
import com.myhomelibcorp.domain.model.readinglist.ReadingListId;
import com.myhomelibcorp.domain.model.readinglist.ReadingListItem;
import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ReadingListRepository {
    ReadingList save(ReadingList list);
    Optional<ReadingList> findById(ReadingListId id);
    Optional<ReadingList> findByName(String name);
    List<ReadingList> findAll();
    Map<ReadingListId, Integer> itemCounts();
    void delete(ReadingListId id);
    List<ReadingListItem> findItems(ReadingListId id);
    void addBooks(ReadingListId id, List<BookId> bookIds);
    void removeBooks(ReadingListId id, List<BookId> bookIds);
    void reorder(ReadingListId id, List<BookId> orderedBookIds);
    Optional<BookId> findResumeBookId(ReadingListId id);
}
