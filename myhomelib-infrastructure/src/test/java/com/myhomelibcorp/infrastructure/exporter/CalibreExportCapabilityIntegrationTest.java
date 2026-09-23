package com.myhomelibcorp.infrastructure.exporter;

import com.myhomelibcorp.application.action.BookActionExecutionService;
import com.myhomelibcorp.application.action.BookActionProfileService;
import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.export.ExportCompletionService;
import com.myhomelibcorp.application.export.ExportHistoryService;
import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.application.usecase.conversion.ConvertBookUseCase;
import com.myhomelibcorp.application.usecase.export.ExportToDeviceUseCase;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CalibreExportCapabilityIntegrationTest {
    @TempDir Path temp;

    @Test
    void configuredCalibreExposesAllExportTargetsAndActuallyConvertsFb2ToAzw3() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"));

        Path fakeCalibre = temp.resolve("ebook-convert");
        Files.writeString(fakeCalibre, "#!/bin/sh\ncp -- \"$1\" \"$2\"\n", StandardCharsets.UTF_8);
        Assumptions.assumeTrue(fakeCalibre.toFile().setExecutable(true, true) || Files.isExecutable(fakeCalibre));

        MemorySettings settings = new MemorySettings();
        settings.put("converter.calibre.executable", fakeCalibre.toString());
        settings.put("converter.calibre.timeoutSeconds", "30");

        CalibreConverterConfiguration calibre = new CalibreConverterConfiguration();
        List<BookConverter> converters = new ArrayList<>(List.of(
                new Fb2BookConverter(), new Fb2ZipBookConverter(), new TxtBookConverter(), new EpubBookConverter(),
                calibre.calibreEpubConverter(settings), calibre.calibreMobiConverter(settings),
                calibre.calibreAzw3Converter(settings), calibre.calibrePdfConverter(settings),
                calibre.calibreFb2Converter(settings), calibre.calibreTxtConverter(settings),
                calibre.calibreDocxConverter(settings), calibre.calibreHtmlzConverter(settings),
                calibre.calibreKepubConverter(settings), calibre.calibreLitConverter(settings),
                calibre.calibreLrfConverter(settings), calibre.calibrePdbConverter(settings),
                calibre.calibrePmlzConverter(settings), calibre.calibreRbConverter(settings),
                calibre.calibreRtfConverter(settings), calibre.calibreSnbConverter(settings),
                calibre.calibreTcrConverter(settings), calibre.calibreTxtzConverter(settings),
                calibre.calibreZipConverter(settings)
        ));

        BookId id = BookId.generate();
        Book book = Book.builder().id(id).title("Calibre integration")
                .file(new BookFile("source.fb2", "", "", 10, temp.toString()))
                .local(true).build();
        BookQueryRepository books = mock(BookQueryRepository.class);
        BookResourcePort resources = mock(BookResourcePort.class);
        when(books.findById(id)).thenReturn(Optional.of(book));
        when(resources.locateBookFile(book)).thenReturn(Optional.of(temp.resolve("source.fb2")));
        when(resources.readBookData(book)).thenAnswer(ignored -> Optional.of(
                new ByteArrayInputStream("FB2-SOURCE".getBytes(StandardCharsets.UTF_8))));

        ConvertBookUseCase conversion = new ConvertBookUseCase(books, converters, resources,
                mock(CommittedCatalogMutationService.class));
        ExportToDeviceUseCase export = new ExportToDeviceUseCase(books, converters, resources, settings,
                mock(BookActionProfileService.class), mock(BookActionExecutionService.class),
                mock(ExportHistoryService.class), mock(ExportCompletionService.class), conversion, activeLifecycle(), new com.myhomelibcorp.application.operation.LibraryOperationCoordinator());

        Set<ExportRequest.ExportFormat> everyExportFormat = EnumSet.allOf(ExportRequest.ExportFormat.class);
        assertThat(export.supportedFormats()).containsExactlyInAnyOrderElementsOf(everyExportFormat);
        assertThat(export.supportedFormatsForBooks(List.of(id))).containsExactlyInAnyOrderElementsOf(everyExportFormat);

        Path destination = temp.resolve("device");
        ExportRequest request = ExportRequest.builder()
                .collectionId("c1")
                .bookIds(List.of(id))
                .destinationFolder(destination)
                .format(ExportRequest.ExportFormat.AZW3)
                .collisionPolicy(ExportRequest.CollisionPolicy.OVERWRITE)
                .customFileNameTemplate("converted")
                .subfolderTemplate("safe")
                .build();

        ExportToDeviceUseCase.ExportResult result = export.execute(request);
        assertThat(result.failed()).isZero();
        assertThat(result.errors()).isEmpty();
        assertThat(result.exported()).isOne();
        assertThat(Files.readString(destination.resolve("safe/converted.azw3"), StandardCharsets.UTF_8))
                .isEqualTo("FB2-SOURCE");
    }

    private static final class MemorySettings implements ApplicationSettingsPort {
        private final Map<String, String> values = new HashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String, String> result = new HashMap<>();
            values.forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
            return result;
        }
        @Override public boolean getBoolean(String key, boolean defaultValue) {
            return Boolean.parseBoolean(values.getOrDefault(key, Boolean.toString(defaultValue)));
        }
    }

    private static com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort activeLifecycle() {
        var lifecycle = org.mockito.Mockito.mock(
                com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort.class);
        org.mockito.Mockito.when(lifecycle.getCurrentCollection()).thenReturn(
                new com.myhomelibcorp.domain.model.collection.Collection(
                        "c1", "Test", null, null, 0, null, null, null, null));
        return lifecycle;
    }
}
