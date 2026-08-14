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
public class BookDocument {
    private BookMetadata metadata;
    private List<Chapter> chapters;
    private List<ImageData> images;
    private List<Note> footnotes;

    public List<Chapter> getChapters() {
        if (chapters == null) {
            chapters = new ArrayList<>();
        }
        return chapters;
    }

    public List<ImageData> getImages() {
        if (images == null) {
            images = new ArrayList<>();
        }
        return images;
    }

    public List<Note> getFootnotes() {
        if (footnotes == null) {
            footnotes = new ArrayList<>();
        }
        return footnotes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Note {
        private String id;
        private String content;
    }
}