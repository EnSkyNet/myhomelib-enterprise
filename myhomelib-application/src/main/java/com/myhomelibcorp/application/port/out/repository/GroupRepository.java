package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.domain.model.group.Group;

import java.util.List;
import java.util.Optional;

public interface GroupRepository {
    List<Group> findAll();
    Optional<Group> findById(Long id);
    Optional<Group> findByName(String name);
    Group save(Group group);
    void deleteById(Long id);
    void deleteAllBooksFromGroup(Long groupId);
    void addBookToGroup(Long groupId, String bookId);
    void removeBookFromGroup(Long groupId, String bookId);
    List<String> findBookIdsByGroup(Long groupId);
}