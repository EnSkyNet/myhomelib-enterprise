package com.myhomelibcorp.reader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Chapter {
    private String id;
    private String title;
    private int level;
    private String content;
    private List<Chapter> children;
    private String paragraphId;

    // ВИДАЛЕНО: private int startOffset;
    // ВИДАЛЕНО: private int endOffset;

    public List<Chapter> getChildren() {
        if (children == null) {
            children = new ArrayList<>();
        }
        return children;
    }

    public boolean hasChildren() {
        return children != null && !children.isEmpty();
    }

    public boolean hasContent() {
        return content != null && !content.isEmpty();
    }

    public boolean hasParagraphId() {
        return paragraphId != null && !paragraphId.isEmpty();
    }

    @Override
    public String toString() {
        return title != null ? title : "Розділ";
    }
}