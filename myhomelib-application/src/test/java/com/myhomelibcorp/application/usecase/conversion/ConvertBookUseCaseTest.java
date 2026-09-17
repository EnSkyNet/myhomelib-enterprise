package com.myhomelibcorp.application.usecase.conversion;

import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConvertBookUseCaseTest {

    @TempDir Path temp;

    @Test
    void exposesProviderNeutralCapabilityMatrix() {
        Fixture f = new Fixture(new CopyConverter("test-copy", Set.of("fb2", "txt"), "epub", ".epub"));

        assertThat(f.useCase.capabilityMatrix()).singleElement().satisfies(view -> {
            assertThat(view.converterId()).isEqualTo("test-copy");
            assertThat(view.available()).isTrue();
            assertThat(view.capability().sourceFormats()).containsExactlyInAnyOrder("fb2", "txt");
            assertThat(view.capability().targetFormat()).isEqualTo("epub");
        });
    }

    @Test
    void selectedBookCapabilityMatrixHidesTargetsThatCannotUseAnyAvailableArtifact() {
        Fixture f = new Fixture(new CopyConverter("txt-only", Set.of("txt"), "epub", ".epub"));

        assertThat(f.useCase.capabilityMatrix(f.id)).isEmpty();
        assertThat(f.useCase.capabilityMatrix()).singleElement()
                .satisfies(view -> assertThat(view.capability().targetFormat()).isEqualTo("epub"));
    }

    @Test
    void runtimePluginConverterAppearsAndDisappearsWithoutRecreatingUseCase() {
        Fixture f = new Fixture(new CopyConverter("core-txt", Set.of("txt"), "epub", ".epub"));
        RuntimeExtensionRegistry registry = new RuntimeExtensionRegistry();
        f.useCase.setRuntimeExtensions(registry);
        BookConverter plugin = new CopyConverter("plugin-calibre", Set.of("fb2"), "azw3", ".azw3");

        assertThat(f.useCase.capabilityMatrix(f.id)).isEmpty();

        registry.replacePlugin("converter.calibre", new RuntimeExtensionRegistry.ExtensionBundle(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(plugin), List.of(), List.of(), List.of()));
        assertThat(f.useCase.capabilityMatrix(f.id))
                .extracting(view -> view.capability().targetFormat())
                .containsExactly("azw3");

        registry.removePlugin("converter.calibre");
        assertThat(f.useCase.capabilityMatrix(f.id)).isEmpty();
    }

    @Test
    void successfulJobUsesStagingAndRegistersOutputArtifact() throws Exception {
        Fixture f = new Fixture(new CopyConverter("test-copy", Set.of("fb2"), "epub", ".epub"));
        Path output = temp.resolve("converted");

        ConvertBookUseCase.Result result = f.useCase.execute(new ConvertBookUseCase.Request(
                f.id, "EPUB", output, true, 1024, 4096, () -> false));

        assertThat(result.converterId()).isEqualTo("test-copy");
        Path finalFile = Path.of(result.artifact().getFile().getFullPath());
        assertThat(finalFile).exists();
        assertThat(Files.readString(finalFile)).isEqualTo("SOURCE-FB2");
        assertThat(result.artifact().getFormat()).isEqualTo("epub");
        assertThat(result.artifact().getSha256()).hasSize(64);
        assertThat(result.artifact().getMetadata()).containsEntry("conversion.provider", "test-copy");

        ArgumentCaptor<BookArtifact> artifact = ArgumentCaptor.forClass(BookArtifact.class);
        verify(f.mutations).upsertArtifact(eq(f.id), artifact.capture(), eq(true));
        assertThat(artifact.getValue().getId()).isEqualTo(result.artifact().getId());
        assertNoStagingFiles(output);
    }

    @Test
    void providerFailureLeavesNoFilesAndDoesNotRegisterArtifact() throws Exception {
        BookConverter broken = new CopyConverter("broken", Set.of("fb2"), "epub", ".epub") {
            @Override public void convert(Book book, InputStream sourceStream, Path targetFile) throws Exception {
                Files.writeString(targetFile, "PARTIAL");
                throw new IllegalStateException("boom");
            }
        };
        Fixture f = new Fixture(broken);
        Path output = temp.resolve("broken");

        assertThatThrownBy(() -> f.useCase.execute(new ConvertBookUseCase.Request(
                f.id, "epub", output, false, 1024, 4096, () -> false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("boom");

        verifyNoInteractions(f.mutations);
        assertDirectoryHasNoFiles(output);
    }

    @Test
    void rejectsOversizedInputBeforeProviderStarts() throws Exception {
        CopyConverter converter = spy(new CopyConverter("copy", Set.of("fb2"), "epub", ".epub"));
        Fixture f = new Fixture(converter, 50);

        assertThatThrownBy(() -> f.useCase.execute(new ConvertBookUseCase.Request(
                f.id, "epub", temp.resolve("limit-in"), false, 10, 4096, () -> false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("input exceeds limit");

        verify(converter, never()).convert(any(), any(), any(Path.class));
        verifyNoInteractions(f.mutations);
    }

    @Test
    void oversizedOutputIsRemovedAndNotRegistered() throws Exception {
        BookConverter large = new CopyConverter("large", Set.of("fb2"), "epub", ".epub") {
            @Override public void convert(Book book, InputStream sourceStream, Path targetFile) throws Exception {
                Files.write(targetFile, new byte[256]);
            }
        };
        Fixture f = new Fixture(large);
        Path output = temp.resolve("limit-out");

        assertThatThrownBy(() -> f.useCase.execute(new ConvertBookUseCase.Request(
                f.id, "epub", output, false, 1024, 32, () -> false)))
                .isInstanceOf(Exception.class).hasMessageContaining("output exceeds limit");

        verifyNoInteractions(f.mutations);
        assertDirectoryHasNoFiles(output);
    }

    @Test
    void exactInputLimitStillAllowsProviderToObserveEof() throws Exception {
        Fixture f = new Fixture(new CopyConverter("exact-limit", Set.of("fb2"), "epub", ".epub"));
        Path output = temp.resolve("exact-limit");

        ConvertBookUseCase.Result result = f.useCase.execute(new ConvertBookUseCase.Request(
                f.id, "epub", output, false, "SOURCE-FB2".length(), 4096, () -> false));

        assertThat(Path.of(result.artifact().getFile().getFullPath())).exists();
        verify(f.mutations).upsertArtifact(eq(f.id), any(BookArtifact.class), eq(false));
        assertNoStagingFiles(output);
    }

    @Test
    void registrationFailureRemovesCommittedOutput() throws Exception {
        Fixture f = new Fixture(new CopyConverter("registration-failure", Set.of("fb2"), "epub", ".epub"));
        Path output = temp.resolve("registration-failure");
        doThrow(new IllegalStateException("db unavailable"))
                .when(f.mutations).upsertArtifact(eq(f.id), any(BookArtifact.class), eq(false));

        assertThatThrownBy(() -> f.useCase.execute(new ConvertBookUseCase.Request(
                f.id, "epub", output, false, 1024, 4096, () -> false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("db unavailable");

        assertDirectoryHasNoFiles(output);
    }

    @Test
    void choosesCompatibleAlternativeArtifactWhenPreferredFormatCannotBeConverted() throws Exception {
        BookId id = BookId.generate();
        BookArtifact epub = BookArtifact.builder().id("preferred-epub").format("epub").local(true)
                .file(new BookFile("preferred.epub", "", "", 4, temp.toString())).build();
        BookArtifact fb2 = BookArtifact.builder().id("fallback-fb2").format("fb2").local(true)
                .file(new BookFile("fallback.fb2", "", "", 10, temp.toString())).build();
        Book book = Book.builder().id(id).title("Alternatives").file(epub.getFile())
                .artifacts(List.of(epub, fb2)).preferredArtifactId(epub.getId()).local(true).build();
        BookQueryRepository books = mock(BookQueryRepository.class);
        BookResourcePort resources = mock(BookResourcePort.class);
        CommittedCatalogMutationService mutations = mock(CommittedCatalogMutationService.class);
        when(books.findById(id)).thenReturn(Optional.of(book));
        when(resources.readBookData(eq("fallback.fb2"), anyString(), eq(temp.toString()), anyString()))
                .thenReturn(Optional.of(new ByteArrayInputStream("SOURCE-FB2".getBytes(StandardCharsets.UTF_8))));
        ConvertBookUseCase useCase = new ConvertBookUseCase(books,
                List.of(new CopyConverter("fb2-to-txt", Set.of("fb2"), "txt", ".txt")), resources, mutations);

        ConvertBookUseCase.Result result = useCase.execute(new ConvertBookUseCase.Request(
                id, "txt", temp.resolve("alternatives"), false, 1024, 4096, () -> false));

        assertThat(Files.readString(Path.of(result.artifact().getFile().getFullPath()))).isEqualTo("SOURCE-FB2");
        assertThat(result.artifact().getMetadata()).containsEntry("conversion.sourceArtifactId", "fallback-fb2");
        verify(resources, never()).readBookData(eq("preferred.epub"), anyString(), anyString(), anyString());
    }

    @Test
    void cancellationObservedDuringSourceReadCleansStaging() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        BookConverter cancellable = new CopyConverter("cancel", Set.of("fb2"), "epub", ".epub") {
            @Override public void convert(Book book, InputStream sourceStream, Path targetFile) throws Exception {
                assertThat(sourceStream.read()).isNotNegative();
                cancelled.set(true);
                sourceStream.read();
            }
        };
        Fixture f = new Fixture(cancellable);
        Path output = temp.resolve("cancelled");

        assertThatThrownBy(() -> f.useCase.execute(new ConvertBookUseCase.Request(
                f.id, "epub", output, false, 1024, 4096, cancelled::get)))
                .isInstanceOf(CancellationException.class);

        verifyNoInteractions(f.mutations);
        assertDirectoryHasNoFiles(output);
    }

    private void assertNoStagingFiles(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            assertThat(files.noneMatch(p -> p.getFileName().toString().startsWith(".mhl-convert-"))).isTrue();
        }
    }

    private void assertDirectoryHasNoFiles(Path directory) throws Exception {
        if (!Files.exists(directory)) return;
        try (var files = Files.list(directory)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    private final class Fixture {
        final BookId id = BookId.generate();
        final Book book;
        final BookQueryRepository books = mock(BookQueryRepository.class);
        final BookResourcePort resources = mock(BookResourcePort.class);
        final CommittedCatalogMutationService mutations = mock(CommittedCatalogMutationService.class);
        final ConvertBookUseCase useCase;

        Fixture(BookConverter converter) { this(converter, "SOURCE-FB2".length()); }

        Fixture(BookConverter converter, long declaredSize) {
            BookArtifact source = BookArtifact.builder().id("source-artifact").format("fb2").local(true)
                    .file(new BookFile("source.fb2", "", "", declaredSize, temp.toString())).build();
            book = Book.builder().id(id).title("A Book").file(source.getFile())
                    .artifacts(List.of(source)).preferredArtifactId(source.getId()).local(true).build();
            when(books.findById(id)).thenReturn(Optional.of(book));
            when(resources.readBookData(eq("source.fb2"), anyString(), eq(temp.toString()), anyString()))
                    .thenAnswer(ignored -> Optional.of(new ByteArrayInputStream("SOURCE-FB2".getBytes(StandardCharsets.UTF_8))));
            useCase = new ConvertBookUseCase(books, List.of(converter), resources, mutations);
        }
    }

    private static class CopyConverter implements BookConverter {
        private final String id;
        private final Set<String> sources;
        private final String target;
        private final String extension;

        private CopyConverter(String id, Set<String> sources, String target, String extension) {
            this.id = id;
            this.sources = sources;
            this.target = target;
            this.extension = extension;
        }

        @Override public String id() { return id; }
        @Override public boolean supports(Book book) { return true; }
        @Override public String getTargetExtension() { return extension; }
        @Override public String getFormatName() { return target; }
        @Override public Set<BookConversionCapability> capabilities() {
            return Set.of(new BookConversionCapability(sources, target, extension));
        }
        @Override public void convert(Book book, InputStream sourceStream, Path targetFile) throws Exception {
            Files.copy(sourceStream, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
