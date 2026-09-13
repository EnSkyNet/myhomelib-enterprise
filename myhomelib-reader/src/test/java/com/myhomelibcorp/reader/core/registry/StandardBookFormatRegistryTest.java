package com.myhomelibcorp.reader.core.registry;

import com.myhomelibcorp.reader.api.BookFormat;
import com.myhomelibcorp.reader.api.BookParser;
import com.myhomelibcorp.reader.api.BookSource;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StandardBookFormatRegistryTest {

    @Test
    void standardRegistryContainsAllBuiltInReaderFormats() {
        DefaultBookFormatRegistry registry = DefaultBookFormatRegistry.standard();

        assertThat(registry.findByExtension("fb2")).isPresent();
        assertThat(registry.findByExtension("epub")).isPresent();
        assertThat(registry.findByExtension("txt")).isPresent();
        assertThat(registry.findByExtension("zip")).isPresent();
        assertThat(registry.getAllFormats()).extracting(BookFormat::id)
                .containsExactlyInAnyOrder("fb2", "epub", "txt", "zip");
    }

    @Test
    void eachStandardRegistryRemainsIndependentlyExtensible() {
        DefaultBookFormatRegistry first = DefaultBookFormatRegistry.standard();
        DefaultBookFormatRegistry second = DefaultBookFormatRegistry.standard();
        first.register(new DummyFormat());

        assertThat(first.findByExtension("dummy")).isPresent();
        assertThat(second.findByExtension("dummy")).isEmpty();
    }

    private static final class DummyFormat implements BookFormat {
        @Override public String id() { return "dummy"; }
        @Override public String displayName() { return "Dummy"; }
        @Override public Set<String> extensions() { return Set.of("dummy"); }
        @Override public boolean supports(BookSource source) { return source != null && "dummy".equals(source.extension()); }
        @Override public BookParser createParser() { throw new UnsupportedOperationException("not used"); }
    }
}
