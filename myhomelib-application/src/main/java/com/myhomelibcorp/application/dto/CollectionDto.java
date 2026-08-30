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

    @Override
    public String toString() {
        return name != null && !name.isBlank() ? name : "Без назви";
    }
}