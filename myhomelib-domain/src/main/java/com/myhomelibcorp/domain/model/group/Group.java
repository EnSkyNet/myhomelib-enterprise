package com.myhomelibcorp.domain.model.group;

import com.myhomelibcorp.domain.model.valueobject.GroupId;
import lombok.Getter;

@Getter
public class Group {
    private final GroupId id;
    private final String name;
    private final boolean allowDelete;

    public Group(GroupId id, String name, boolean allowDelete) {
        this.id = id;
        this.name = name;
        this.allowDelete = allowDelete;
    }

    public Group(String name, boolean allowDelete) {
        this.id = GroupId.fromLong(null);
        this.name = name;
        this.allowDelete = allowDelete;
    }

    public Group rename(String newName) {
        return new Group(this.id, newName, this.allowDelete);
    }

    public Long getIdAsLong() {
        return id.asLong();
    }

    @Override
    public String toString() {
        return name;
    }
}