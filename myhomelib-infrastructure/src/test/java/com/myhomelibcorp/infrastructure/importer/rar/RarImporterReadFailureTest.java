package com.myhomelibcorp.infrastructure.importer.rar;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;
import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RarImporterReadFailureTest {
    @TempDir Path temp;

    @Test
    void entryInputErrorPropagatesToImportInsteadOfLookingComplete() throws Exception {
        Archive archive = mock(Archive.class);
        FileHeader header = mock(FileHeader.class);
        when(header.getFileName()).thenReturn("chapter.txt");
        InputStream broken = mock(InputStream.class);
        when(broken.read(any(byte[].class))).thenThrow(new IOException("disk read failed"));
        when(archive.getInputStream(header)).thenReturn(broken);
        ImporterRegistry registry = mock(ImporterRegistry.class);
        when(registry.findImporter(any(Path.class))).thenReturn(mock(BookImporterPort.class));
        RarImporter importer = new RarImporter();
        Field field = RarImporter.class.getDeclaredField("importerRegistry");
        field.setAccessible(true);
        field.set(importer, registry);
        Class<?> type = Class.forName(RarImporter.class.getName() + "$RarIterator");
        Constructor<?> ctor = type.getDeclaredConstructor(RarImporter.class, Path.class, Archive.class, List.class);
        ctor.setAccessible(true);
        Iterator<?> iterator = (Iterator<?>) ctor.newInstance(importer,
                temp.resolve("broken.rar"), archive, List.of(header));

        assertThrows(UncheckedIOException.class, iterator::hasNext);
        verify(archive).close();
    }
}
