package com.myhomelibcorp.infrastructure.importer.rar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;
import com.github.junrar.rarfile.MainHeader;
import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RarImporterSafetyTest {

    @Test
    @SuppressWarnings("unchecked")
    void entryLimitDoesNotSilentlyEndWithPartialImport(@TempDir Path dir) throws Exception {
        RarImporter importer = new RarImporter();
        Archive archive = mock(Archive.class);
        FileHeader nextHeader = mock(FileHeader.class);
        Class<?> iteratorType = Class.forName(RarImporter.class.getName() + "$RarIterator");
        Constructor<?> ctor = iteratorType.getDeclaredConstructor(RarImporter.class, Path.class, Archive.class, List.class);
        ctor.setAccessible(true);
        Iterator<?> iterator = (Iterator<?>) ctor.newInstance(importer, dir.resolve("books.rar"), archive, List.of(nextHeader));
        Field count = iteratorType.getDeclaredField("entryCount");
        count.setAccessible(true);
        count.setInt(iterator, ArchiveSafetyLimits.MAX_ENTRY_COUNT);

        assertThatThrownBy(iterator::hasNext)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entry safety limit");
    }

    @Test
    @SuppressWarnings("unchecked")
    void solidRarConsumesSkippedMembersThroughBoundedReader(@TempDir Path dir) throws Exception {
        RarImporter importer = new RarImporter();
        BookImporterPort txt = new BookImporterPort() {
            @Override public boolean supports(Path file) {
                return file.toString().toLowerCase(Locale.ROOT).endsWith(".txt");
            }
            @Override public Stream<Book> importBooks(Path file) {
                try {
                    return Stream.of(Book.builder().title(Files.readString(file, StandardCharsets.UTF_8).trim())
                            .file(BookFile.empty()).build());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            @Override public String getFormatName() { return "TXT-test"; }
            @Override public long countBooks(Path file) { return 1; }
        };
        ImporterRegistry registry = new ImporterRegistry() {
            @Override public BookImporterPort findImporter(Path file) {
                if (txt.supports(file)) return txt;
                throw new IllegalArgumentException("unsupported");
            }
            @Override public List<BookImporterPort> getAllImporters() { return List.of(txt); }
            @Override public List<String> getSupportedFormats() { return List.of("TXT-test"); }
        };
        Field registryField = RarImporter.class.getDeclaredField("importerRegistry");
        registryField.setAccessible(true);
        registryField.set(importer, registry);

        Archive archive = mock(Archive.class);
        MainHeader mainHeader = mock(MainHeader.class);
        when(archive.getMainHeader()).thenReturn(mainHeader);
        when(mainHeader.isSolid()).thenReturn(true);
        FileHeader skipped = mock(FileHeader.class);
        when(skipped.getFileName()).thenReturn("notes.bin");
        when(skipped.getFullUnpackSize()).thenReturn(7L);
        when(skipped.isDirectory()).thenReturn(false);
        when(skipped.isSolid()).thenReturn(false);
        FileHeader book = mock(FileHeader.class);
        when(book.getFileName()).thenReturn("good.txt");
        when(book.getFullUnpackSize()).thenReturn(4L);
        when(book.isDirectory()).thenReturn(false);
        when(book.isSolid()).thenReturn(true);
        when(archive.getInputStream(skipped)).thenReturn(new ByteArrayInputStream("ignored".getBytes(StandardCharsets.UTF_8)));
        when(archive.getInputStream(book)).thenReturn(new ByteArrayInputStream("Good".getBytes(StandardCharsets.UTF_8)));

        Class<?> iteratorType = Class.forName(RarImporter.class.getName() + "$RarIterator");
        Constructor<?> ctor = iteratorType.getDeclaredConstructor(RarImporter.class, Path.class, Archive.class, List.class);
        ctor.setAccessible(true);
        Iterator<Book> iterator = (Iterator<Book>) ctor.newInstance(importer, dir.resolve("solid.rar"), archive, List.of(skipped, book));

        assertThat(iterator.hasNext()).isTrue();
        assertThat(iterator.next().getTitle()).isEqualTo("Good");
        assertThat(iterator.hasNext()).isFalse();
    }

    @Test
    void rejectsRarThroughSymlinkedParentBeforeJunrarOpensPath(@TempDir Path dir) throws Exception {
        Path realDir = Files.createDirectory(dir.resolve("real-rar"));
        Files.writeString(realDir.resolve("books.rar"), "not-even-a-rar");
        Path alias = dir.resolve("alias-rar");
        Files.createSymbolicLink(alias, realDir.getFileName());

        RarImporter importer = new RarImporter();

        assertThatThrownBy(() -> importer.importBooks(alias.resolve("books.rar")))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(java.io.IOException.class);
    }
}
