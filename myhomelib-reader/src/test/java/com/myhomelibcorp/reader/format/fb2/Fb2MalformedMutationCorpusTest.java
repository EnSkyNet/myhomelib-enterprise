package com.myhomelibcorp.reader.format.fb2;

import com.myhomelibcorp.reader.api.BookSource;
import com.myhomelibcorp.reader.api.ParseOptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Deterministic malformed-XML mutation corpus for the streaming FB2 reader. */
class Fb2MalformedMutationCorpusTest {
    @Test
    void malformedAndTruncatedMutationsAreRejectedAsControlledIoFailures() {
        String valid = """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
                  <description><title-info><book-title>Mutation</book-title><lang>uk</lang></title-info></description>
                  <body><section><p>Текст книги.</p></section></body>
                </FictionBook>
                """;
        byte[] source = valid.getBytes(StandardCharsets.UTF_8);
        List<byte[]> mutations = List.of(
                java.util.Arrays.copyOf(source, 1),
                java.util.Arrays.copyOf(source, source.length / 3),
                java.util.Arrays.copyOf(source, source.length / 2),
                java.util.Arrays.copyOf(source, source.length - 2),
                valid.replace("</section>", "</broken>").getBytes(StandardCharsets.UTF_8));

        for (int i = 0; i < mutations.size(); i++) {
            byte[] bytes = mutations.get(i);
            assertThatThrownBy(() -> new Fb2StreamingParser().parse(new BytesSource(bytes), ParseOptions.defaultOptions()))
                    .as("FB2 malformed mutation %s", i)
                    .isInstanceOf(IOException.class);
        }
    }

    private record BytesSource(byte[] bytes) implements BookSource {
        @Override public InputStream openStream() { return new ByteArrayInputStream(bytes); }
        @Override public OptionalLong size() { return OptionalLong.of(bytes.length); }
        @Override public String name() { return "mutation.fb2"; }
        @Override public String extension() { return "fb2"; }
        @Override public String id() { return "mutation"; }
    }
}
