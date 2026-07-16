package com.myhomelibcorp.ui.model.navigation;

import com.myhomelibcorp.domain.model.group.Group;

public record GroupNode(Group group) implements LibraryNode {
    @Override
    public String toString() {
        return group != null ? group.getName() : "Група";
    }
}