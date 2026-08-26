package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.ReaderPosition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderPageHistoryTest {
    @Test
    void keepsOnlyBoundedMostRecentPositions() {
        ReaderPageHistory history = new ReaderPageHistory(2);
        history.push(new ReaderPosition(0, 10, 0, 0));
        history.push(new ReaderPosition(0, 20, 0, 0));
        history.push(new ReaderPosition(0, 30, 0, 0));

        assertThat(history.pollLast().textOffset()).isEqualTo(30);
        assertThat(history.pollLast().textOffset()).isEqualTo(20);
        assertThat(history.pollLast()).isNull();
    }
}
