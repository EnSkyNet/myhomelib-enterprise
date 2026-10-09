package com.myhomelibcorp.reader.core.text;

import com.myhomelibcorp.reader.api.TextStyle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextStorageImplBoundsTest {

    @Test
    void getSpansUsesClampedRangeAsFragmentOrigin() {
        TextStorageImpl storage = new TextStorageImpl();
        storage.append("abcdef", TextStyle.BOLD);

        var spans = storage.getSpans(-5, 3);

        assertThat(spans).hasSize(1);
        assertThat(spans.getFirst().start()).isZero();
        assertThat(spans.getFirst().end()).isEqualTo(3);
        assertThat(spans.getFirst().style()).isEqualTo(TextStyle.BOLD);
    }

    @Test
    void getSpansReturnsEmptyForRangeOutsideText() {
        TextStorageImpl storage = new TextStorageImpl();
        storage.append("abcdef", TextStyle.BOLD);

        assertThat(storage.getSpans(10, 20)).isEmpty();
        assertThat(storage.getSpans(-10, -1)).isEmpty();
    }
}
