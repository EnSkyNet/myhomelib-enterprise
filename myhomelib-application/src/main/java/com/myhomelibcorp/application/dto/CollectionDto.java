package com.myhomelibcorp.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollectionDto {
    private String id;
    private String name;
    private boolean active;
    private boolean allowRename;
    private boolean allowDelete;
    private String rootFolder;
    private String dbFile;
    private int type;
    @Builder.Default
    private long booksCount = -1L;

    /**
     * Compatibility constructor for older presenters.
     */
    public CollectionDto(String id, String name, boolean allowDelete) {
        this(id, name, false, allowDelete, allowDelete, null, null, 0, -1L);
    }

    @Override
    public String toString() {
        return name != null && !name.isBlank() ? name : "Без назви";
    }
}