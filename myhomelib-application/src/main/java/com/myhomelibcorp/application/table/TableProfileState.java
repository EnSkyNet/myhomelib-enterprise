package com.myhomelibcorp.application.table;

import java.util.List;

public record TableProfileState(List<TableColumnProfile> columns, String sortColumn, String sortDirection) {
    public TableProfileState {
        columns = columns == null ? List.of() : List.copyOf(columns);
        sortColumn = sortColumn == null ? "" : sortColumn;
        sortDirection = sortDirection == null ? "" : sortDirection;
    }
}
