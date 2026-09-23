package com.myhomelibcorp.converter.toolchain;

import com.myhomelibcorp.application.conversion.BookConversionContext;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.converter.toolchain.metadata.Fb2MetadataPatcher;
import com.myhomelibcorp.converter.toolchain.validation.ConversionOutputValidator;
import com.myhomelibcorp.converter.toolchain.validation.ConversionOutputValidators;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.epub.optimizer.EpubAcceptanceMode;
import com.myhomelibcorp.epub.optimizer.EpubOptimizationProfile;
import com.myhomelibcorp.epub.optimizer.EpubOptimizationReport;
import com.myhomelibcorp.epub.optimizer.EpubOptimizer;
import com.myhomelibcorp.epub.optimizer.profile.ReadingSystemProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolchainBookConverterPublicationTest {
    @TempDir Path tempDir;

    @Test
    void finalValidationFailureDoesNotOverwriteExistingTarget() throws Exception {
        Path target = tempDir.resolve("book.epub");
        Files.writeString(target, "existing-target", StandardCharsets.UTF_8);
        AtomicInteger validationCalls = new AtomicInteger();

        ConversionOutputValidator validator = new ConversionOutputValidator() {
            @Override public boolean supports(String format) { return "epub".equals(format); }
            @Override public void validate(Path file, long maxOutputBytes) throws ConversionToolchainException {
                if (validationCalls.incrementAndGet() >= 2) {
                    throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID,
                            "reject publication copy");
                }
            }
        };
        ConversionEngine engine = new ConversionEngine() {
            @Override public ConversionEngineId id() { return ConversionEngineId.PANDOC; }
            @Override public boolean isAvailable() { return true; }
            @Override public List<ConversionStep> capabilities() {
                return List.of(new ConversionStep(ConversionEngineId.PANDOC, "txt", "epub", ".epub", 100, false, 1));
            }
            @Override public ConversionStepResult convert(ConversionStep step, Path source, Path output,
                                                          java.util.function.BooleanSupplier cancelled,
                                                          long maxOutputBytes) throws ConversionToolchainException {
                try {
                    Files.writeString(output, "candidate", StandardCharsets.UTF_8);
                    return new ConversionStepResult(step, output, 1L, Files.size(output));
                } catch (Exception failure) {
                    throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE, "fixture failed", failure);
                }
            }
        };

        ToolchainBookConverter converter = new ToolchainBookConverter(List.of(engine),
                new ConversionOutputValidators(List.of(validator)), settings(),
                new EpubOptimizer(null, null, null, null, null, null, null, null, null, List.of()),
                new Fb2MetadataPatcher());
        Book book = Book.builder().title("Publication safety").build();
        BookConversionContext context = new BookConversionContext(book, "txt", "epub",
                new ByteArrayInputStream("source".getBytes(StandardCharsets.UTF_8)), target,
                () -> false, 1024 * 1024, false);

        assertThatThrownBy(() -> converter.convert(context))
                .isInstanceOf(ConversionToolchainException.class)
                .hasMessageContaining("reject publication copy");

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("existing-target");
        assertThat(validationCalls.get()).isEqualTo(2);
        try (var stream = Files.list(tempDir)) {
            assertThat(stream.map(path -> path.getFileName().toString())
                    .filter(name -> name.contains(".mhl-publish-"))).isEmpty();
        }
    }


    @Test
    void epubOptimizerFailureIsClassifiedAsOutputInvalidAndNeverPublished() throws Exception {
        Path target = tempDir.resolve("broken.epub");

        ConversionOutputValidator validator = new ConversionOutputValidator() {
            @Override public boolean supports(String format) { return "epub".equals(format); }
            @Override public void validate(Path file, long maxOutputBytes) { }
        };
        ConversionEngine engine = new ConversionEngine() {
            @Override public ConversionEngineId id() { return ConversionEngineId.PANDOC; }
            @Override public boolean isAvailable() { return true; }
            @Override public List<ConversionStep> capabilities() {
                return List.of(new ConversionStep(ConversionEngineId.PANDOC, "txt", "epub", ".epub", 100, false, 1));
            }
            @Override public ConversionStepResult convert(ConversionStep step, Path source, Path output,
                                                          java.util.function.BooleanSupplier cancelled,
                                                          long maxOutputBytes) throws ConversionToolchainException {
                try {
                    Files.writeString(output, "not-a-real-epub", StandardCharsets.UTF_8);
                    return new ConversionStepResult(step, output, 1L, Files.size(output));
                } catch (Exception failure) {
                    throw new ConversionToolchainException(ConversionFailureKind.IO_FAILURE, "fixture failed", failure);
                }
            }
        };
        EpubOptimizer failingOptimizer = new EpubOptimizer(null, null, null, null, null, null, null, null, null, List.of()) {
            @Override
            public EpubOptimizationReport optimize(Path source, Path staged, EpubOptimizationProfile profile,
                                                   ReadingSystemProfile readingSystem,
                                                   EpubAcceptanceMode acceptanceMode) throws Exception {
                throw new javax.xml.stream.XMLStreamException("malformed converter EPUB");
            }
        };

        ToolchainBookConverter converter = new ToolchainBookConverter(List.of(engine),
                new ConversionOutputValidators(List.of(validator)), settings(),
                failingOptimizer, new Fb2MetadataPatcher());
        BookConversionContext context = new BookConversionContext(
                Book.builder().title("Broken EPUB").build(), "txt", "epub",
                new ByteArrayInputStream("source".getBytes(StandardCharsets.UTF_8)), target,
                () -> false, 1024 * 1024, true);

        assertThatThrownBy(() -> converter.convert(context))
                .isInstanceOfSatisfying(ConversionToolchainException.class, failure ->
                        assertThat(failure.kind()).isEqualTo(ConversionFailureKind.OUTPUT_INVALID))
                .hasMessageContaining("EPUB post-processing");
        assertThat(target).doesNotExist();
    }

    private ApplicationSettingsPort settings() {
        return new ApplicationSettingsPort() {
            @Override public String get(String key, String defaultValue) { return defaultValue; }
            @Override public void put(String key, String value) { }
            @Override public void remove(String key) { }
            @Override public Map<String, String> findByPrefix(String prefix) { return Map.of(); }
        };
    }
}
