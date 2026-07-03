package com.myhomelibcorp.ui.model.navigation;

import com.myhomelibcorp.domain.model.series.Series;

public record SeriesNode(Series series) implements LibraryNode {
    @Override
    public String toString() {
        return series != null ? series.getName() : "Серія";
    }
}