package com.myhomelibcorp.infrastructure.importer.sevenz;

import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class SevenZImporterReadFailureTest {
    @TempDir Path temp;

    @Test
    void failureReadingNextEntryIsNotReportedAsEndOfArchive() throws Exception {
        SevenZFile archive = mock(SevenZFile.class);
        when(archive.getNextEntry()).thenThrow(new IOException("broken tail"));
        FileChannel channel = mock(FileChannel.class);
        Class<?> handle = Class.forName(SevenZImporter.class.getName() + "$SevenZHandle");
        Constructor<?> createHandle = handle.getDeclaredConstructor(FileChannel.class, SevenZFile.class);
        createHandle.setAccessible(true);
        Object wrapped = createHandle.newInstance(channel, archive);
        Class<?> type = Class.forName(SevenZImporter.class.getName() + "$SevenZIterator");
        Constructor<?> constructor = type.getDeclaredConstructor(SevenZImporter.class, Path.class, handle);
        constructor.setAccessible(true);
        Iterator<?> iterator = (Iterator<?>) constructor.newInstance(new SevenZImporter(),
                temp.resolve("broken.7z"), wrapped);

        assertThrows(UncheckedIOException.class, iterator::hasNext);
        verify(archive).close();
        verify(channel).close();
    }
}
