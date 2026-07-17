package com.myhomelibcorp.reader.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class Chapter {
    private String id;
    private String title;
    private int level;
    private String content;
    private List<Chapter> children;
    private int startOffset;
    private int endOffset;

    public List<Chapter> getChildren() {
        if (children == null) {
            children = new ArrayList<>();
        }
        return children;
    }
}