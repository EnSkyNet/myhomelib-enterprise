package com.myhomelibcorp.application.extension;

import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RuntimeExtensionRegistryTest {
    @Test
    void replacesAndRemovesPluginOwnedConvertersAtomically() {
        RuntimeExtensionRegistry registry = new RuntimeExtensionRegistry();
        BookConverter first = mock(BookConverter.class);
        BookConverter replacement = mock(BookConverter.class);

        registry.replacePlugin("converter.demo", new RuntimeExtensionRegistry.ExtensionBundle(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(first), List.of(), List.of(), List.of()));
        assertThat(registry.bookConverters()).containsExactly(first);

        registry.replacePlugin("converter.demo", new RuntimeExtensionRegistry.ExtensionBundle(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(replacement), List.of(), List.of(), List.of()));
        assertThat(registry.bookConverters()).containsExactly(replacement);

        assertThat(registry.removePlugin("converter.demo")).isTrue();
        assertThat(registry.bookConverters()).isEmpty();
    }

    @Test
    void presentsProvidersInStablePluginIdOrder() {
        RuntimeExtensionRegistry registry = new RuntimeExtensionRegistry();
        BookConverter a = mock(BookConverter.class);
        BookConverter b = mock(BookConverter.class);
        RuntimeExtensionRegistry.ExtensionBundle bundleA = new RuntimeExtensionRegistry.ExtensionBundle(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(a), List.of(), List.of(), List.of());
        RuntimeExtensionRegistry.ExtensionBundle bundleB = new RuntimeExtensionRegistry.ExtensionBundle(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(b), List.of(), List.of(), List.of());

        registry.replacePlugin("z-plugin", bundleB);
        registry.replacePlugin("a-plugin", bundleA);

        assertThat(registry.bookConverters()).containsExactly(a, b);
    }
}
