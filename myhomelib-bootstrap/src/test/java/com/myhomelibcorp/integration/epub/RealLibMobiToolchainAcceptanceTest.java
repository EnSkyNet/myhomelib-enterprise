package com.myhomelibcorp.integration.epub;

import com.myhomelibcorp.MyHomeLibApp;
import com.myhomelibcorp.application.conversion.BookConversionContext;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.converter.toolchain.ConversionFailureKind;
import com.myhomelibcorp.converter.toolchain.ConversionToolchainException;
import com.myhomelibcorp.converter.toolchain.ToolchainBookConverter;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Opt-in real-process acceptance for the production MOBI -> EPUB path.
 *
 * <p>The positive fixture proves that a real libmobi result can pass STRICT EPUBCheck + reader
 * acceptance. The negative fixture is intentionally one that mobitool exits 0 for but EPUBCheck
 * rejects; MyHomeLib must fail closed and must not publish the target.</p>
 */
@EnabledIfSystemProperty(named = "myhomelib.test.libmobi", matches = "true")
class RealLibMobiToolchainAcceptanceTest {
    @TempDir Path temp;

    @Test
    void strictProductionToolchainAcceptsValidLibmobiOutputAndRejectsInvalidOutput() throws Exception {
        Path mobitool = requiredPath("myhomelib.test.libmobi.executable");
        Path epubcheck = requiredPath("myhomelib.test.epubcheck");
        Path validMobi = requiredPath("myhomelib.test.libmobi.valid");
        Path invalidMobi = requiredPath("myhomelib.test.libmobi.invalid");

        String previousDataDir = System.getProperty("myhomelib.dataDir");
        String previousHeadless = System.getProperty("java.awt.headless");
        try {
            System.setProperty("myhomelib.dataDir", temp.resolve("data").toString());
            System.setProperty("java.awt.headless", "true");
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MyHomeLibApp.class)
                    .web(WebApplicationType.NONE)
                    .properties("spring.main.banner-mode=off", "spring.main.lazy-initialization=false",
                            "logging.level.root=WARN")
                    .run()) {
                ApplicationSettingsPort settings = context.getBean(ApplicationSettingsPort.class);
                settings.put("converter.toolchain.libmobi.executable", mobitool.toString());
                settings.put("converter.epubcheck.executable", epubcheck.toString());
                settings.put("converter.epubAcceptanceMode", "STRICT");

                ToolchainBookConverter converter = context.getBean(ToolchainBookConverter.class);

                Path validTarget = temp.resolve("valid.epub");
                convert(converter, validMobi, validTarget);
                assertThat(validTarget).isRegularFile();
                assertThat(Files.size(validTarget)).isPositive();

                Path invalidTarget = temp.resolve("invalid.epub");
                assertThatThrownBy(() -> convert(converter, invalidMobi, invalidTarget))
                        .isInstanceOfSatisfying(ConversionToolchainException.class, failure ->
                                assertThat(failure.kind()).isEqualTo(ConversionFailureKind.OUTPUT_INVALID));
                assertThat(invalidTarget).doesNotExist();

                System.out.printf("REAL_LIBMOBI_TOOLCHAIN valid=%d invalidPublished=%s%n",
                        Files.size(validTarget), Files.exists(invalidTarget));
            }
        } finally {
            restore("myhomelib.dataDir", previousDataDir);
            restore("java.awt.headless", previousHeadless);
        }
    }

    private void convert(ToolchainBookConverter converter, Path source, Path target) throws Exception {
        Book book = Book.builder()
                .id(BookId.generate())
                .title(source.getFileName().toString())
                .file(new BookFile(source.getFileName().toString(), "", "", Files.size(source),
                        source.getParent().toString()))
                .build();
        try (InputStream in = Files.newInputStream(source)) {
            converter.convert(new BookConversionContext(
                    book, "mobi", "epub", in, target, () -> false, 128L * 1024L * 1024L));
        }
    }

    private Path requiredPath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing system property: " + property);
        Path path = Path.of(value).toAbsolutePath().normalize();
        assertThat(path).isRegularFile();
        return path;
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
