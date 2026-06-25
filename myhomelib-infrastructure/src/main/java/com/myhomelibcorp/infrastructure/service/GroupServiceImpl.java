package com.myhomelibcorp.infrastructure.service;

import com.myhomelibcorp.application.port.out.GroupRepository;
import com.myhomelibcorp.application.port.out.GroupService;
import com.myhomelibcorp.domain.model.group.Group;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class GroupServiceImpl implements GroupService {

    private final GroupRepository groupRepository;

    @Override
    public List<Group> getAllGroups() {
        return groupRepository.findAll();
    }

    @Override
    public Group createGroup(String name) {
        Group group = new Group(name, true);
        return groupRepository.save(group);
    }

    @Override
    public Group renameGroup(Long id, String newName) {
        Group group = groupRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Group not found: " + id));
        Group renamed = group.rename(newName);
        return groupRepository.save(renamed);
    }

    @Override
    public void deleteGroup(Long id) {
        groupRepository.deleteById(id);
    }

    @Override
    public void addBookToGroup(Long groupId, String bookId) {
        groupRepository.addBookToGroup(groupId, bookId);
    }

    @Override
    public void removeBookFromGroup(Long groupId, String bookId) {
        groupRepository.removeBookFromGroup(groupId, bookId);
    }
}