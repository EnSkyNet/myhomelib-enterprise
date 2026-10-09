package com.myhomelibcorp.domain.model.bookmark;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BookmarkUnicodeTest {

    @Test
    void titleTruncationDoesNotSplitSurrogatePair() {
        Bookmark bookmark = Bookmark.builder()
                .id("bookmark-1")
                .bookId("book-1")
                .context("a".repeat(46) + "😀tail")
                .createdAt(LocalDateTime.of(2026, 9, 27, 12, 0))
                .build();

        String title = bookmark.getTitle();

        assertThat(hasUnpairedSurrogate(title)).isFalse();
        assertThat(title).endsWith("...");
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) return true;
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                return true;
            }
        }
        return false;
    }
}
