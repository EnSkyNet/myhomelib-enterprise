package com.myhomelibcorp.application.port.out;

import com.myhomelibcorp.domain.model.group.Group;

import java.util.List;

public interface GroupService {
    List<Group> getAllGroups();
    Group createGroup(String name);
    Group renameGroup(Long id, String newName);
    void deleteGroup(Long id);
    void addBookToGroup(Long groupId, String bookId);
    void removeBookFromGroup(Long groupId, String bookId);
}