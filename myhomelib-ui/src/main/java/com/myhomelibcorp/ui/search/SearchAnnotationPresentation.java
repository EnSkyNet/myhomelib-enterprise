package com.myhomelibcorp.ui.search;

import com.myhomelibcorp.ui.util.Utf16BoundarySupport;

import com.myhomelibcorp.application.annotation.AnnotationManagerItem;
import com.myhomelibcorp.application.annotation.AnnotationManagerType;
import com.myhomelibcorp.ui.service.UiText;

/** Pure text projection for annotation rows shown by the search workspace. */
final class SearchAnnotationPresentation {
    private SearchAnnotationPresentation() { }

    static String summary(AnnotationManagerItem item) {
        if (item == null) return "";
        String text = item.note().isBlank() ? item.quote() : item.note();
        text = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (text.isBlank()) text = item.chapterTitle();
        if (text == null || text.isBlank()) {
            text = item.type() == AnnotationManagerType.NOTE
                    ? UiText.text("ui.annotations.type.note")
                    : UiText.text("ui.annotations.type.highlight");
        }
        if (text.length() <= 140) return text;
        int end = Utf16BoundarySupport.safeCut(text, 137);
        return text.substring(0, end) + "…";
    }

    static String details(AnnotationManagerItem item) {
        if (item == null) return "";
        StringBuilder details = new StringBuilder();
        if (!item.chapterTitle().isBlank()) details.append(item.chapterTitle());
        if (!item.quote().isBlank()) {
            if (!details.isEmpty()) details.append("\n");
            details.append(item.quote());
        }
        if (!item.note().isBlank() && !item.note().equals(item.quote())) {
            if (!details.isEmpty()) details.append("\n");
            details.append(item.note());
        }
        if (!item.tags().isEmpty()) {
            if (!details.isEmpty()) details.append("\n");
            details.append(String.join(", ", item.tags()));
        }
        return details.toString();
    }
}
