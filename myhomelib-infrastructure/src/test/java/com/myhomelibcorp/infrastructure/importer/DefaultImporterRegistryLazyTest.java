package com.myhomelibcorp.infrastructure.importer;

import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DefaultImporterRegistryLazyTest {

    @Test
    void constructorDoesNotResolveImporterGraph() {
        @SuppressWarnings("unchecked")
        ObjectProvider<BookImporterPort> provider = mock(ObjectProvider.class);

        new DefaultImporterRegistry(provider, new RuntimeExtensionRegistry());

        verifyNoInteractions(provider);
    }

    @Test
    void resolvesBuiltInImportersOnlyOnceOnFirstUse() {
        @SuppressWarnings("unchecked")
        ObjectProvider<BookImporterPort> provider = mock(ObjectProvider.class);
        BookImporterPort importer = mock(BookImporterPort.class);
        when(importer.getFormatName()).thenReturn("FB2");
        when(importer.supports(Path.of("book.fb2"))).thenReturn(true);
        when(provider.orderedStream()).thenAnswer(ignored -> Stream.of(importer));
        DefaultImporterRegistry registry = new DefaultImporterRegistry(provider, new RuntimeExtensionRegistry());

        assertThat(registry.getSupportedFormats()).containsExactly("FB2");
        assertThat(registry.findImporter(Path.of("book.fb2"))).isSameAs(importer);

        verify(provider, times(1)).orderedStream();
    }
}
