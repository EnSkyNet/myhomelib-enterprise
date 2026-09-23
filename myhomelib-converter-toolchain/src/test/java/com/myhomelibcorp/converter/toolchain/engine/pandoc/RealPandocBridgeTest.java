package com.myhomelibcorp.converter.toolchain.engine.pandoc;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.converter.toolchain.ConversionStep;
import com.myhomelibcorp.converter.toolchain.discovery.ConverterToolLocator;
import com.myhomelibcorp.converter.toolchain.discovery.ConverterToolProbe;
import com.myhomelibcorp.converter.toolchain.discovery.ConverterToolRegistry;
import com.myhomelibcorp.converter.toolchain.metadata.CanonicalBookMetadata;
import com.myhomelibcorp.converter.toolchain.metadata.Fb2MetadataPatcher;
import com.myhomelibcorp.converter.toolchain.process.ExternalToolRunner;
import com.myhomelibcorp.converter.toolchain.validation.BasicOutputValidator;
import com.myhomelibcorp.converter.toolchain.validation.EpubOutputValidator;
import com.myhomelibcorp.converter.toolchain.validation.Fb2OutputValidator;
import com.myhomelibcorp.converter.toolchain.validation.TextOutputValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in acceptance against a real Pandoc binary. */
@EnabledIfSystemProperty(named = "myhomelib.test.pandoc", matches = ".+")
class RealPandocBridgeTest {
    private static final long MAX_OUTPUT_BYTES = 64L * 1024L * 1024L;
    @TempDir Path tempDir;

    @Test
    void realPandocCoversEpubDocxRtfTxtAndFb2MetadataPatch() throws Exception {
        Path pandoc = Path.of(System.getProperty("myhomelib.test.pandoc")).toAbsolutePath().normalize();
        assertThat(pandoc).isRegularFile();
        ApplicationSettingsPort settings = settings(Map.of(
                "converter.toolchain.pandoc.executable", pandoc.toString(),
                "converter.toolchain.pandoc.timeoutSeconds", "60"));
        ExternalToolRunner runner = new ExternalToolRunner();
        ConverterToolRegistry tools = new ConverterToolRegistry(new ConverterToolLocator(settings), new ConverterToolProbe(runner));
        assertThat(tools.pandoc().available()).as("Pandoc probe: %s", tools.pandoc().diagnostic()).isTrue();
        PandocEngine engine = new PandocEngine(tools, runner, settings);

        Path seed = tempDir.resolve("fixture-seed.md");
        Files.writeString(seed, """
                ---
                title: Fixture Seed
                author: Fixture Author
                lang: uk
                ---

                # Розділ перший

                Реальний UTF-8 текст для інтеграційної перевірки MyHomeLib і Pandoc.

                ## Підрозділ

                Другий абзац із символами: ї, є, ґ, —, «лапки».
                """, StandardCharsets.UTF_8);
        Path epubFixture = generateFixture(pandoc, seed, "epub3", tempDir.resolve("source.epub"));
        Path docxFixture = generateFixture(pandoc, seed, "docx", tempDir.resolve("source.docx"));
        Path txtFixture = tempDir.resolve("source.txt");
        Files.writeString(txtFixture, "# TXT fixture\n\nУкраїнський UTF-8 текст із навігаційним заголовком.\n", StandardCharsets.UTF_8);

        Path epubToTxt = tempDir.resolve("epub-to-txt.txt");
        convert(engine, "epub", "txt", epubFixture, epubToTxt);
        new TextOutputValidator().validate(epubToTxt, MAX_OUTPUT_BYTES);
        assertThat(Files.readString(epubToTxt, StandardCharsets.UTF_8)).contains("Розділ перший", "інтеграційної перевірки");

        Path docxToFb2 = tempDir.resolve("docx-to-fb2.fb2");
        convert(engine, "docx", "fb2", docxFixture, docxToFb2);
        CanonicalBookMetadata metadata = new CanonicalBookMetadata(
                "Канонічна назва MyHomeLib",
                List.of(new CanonicalBookMetadata.CanonicalAuthor("Іван", "", "Тестовий")),
                List.of("sf_fantasy"), "Серія перевірки", 7,
                "Анотація, яку зовнішній конвертер не повинен загубити.", "uk",
                "978-0-00-000000-0", 2026, "MyHomeLib Test Publisher", "",
                new CanonicalBookMetadata.CanonicalCover("", new byte[0]));
        new Fb2MetadataPatcher().patchMissing(docxToFb2, metadata);
        new Fb2OutputValidator().validate(docxToFb2, MAX_OUTPUT_BYTES);
        String fb2 = Files.readString(docxToFb2, StandardCharsets.UTF_8);
        assertThat(fb2).contains("Fixture Seed", "<first-name>Fixture</first-name>", "<last-name>Author</last-name>",
                "<first-name>Іван</first-name>", "<last-name>Тестовий</last-name>", "sf_fantasy", "Серія перевірки",
                "MyHomeLib Test Publisher", "978-0-00-000000-0");
        assertThat(fb2).doesNotContain("Канонічна назва MyHomeLib");

        Path epubToRtf = tempDir.resolve("epub-to-rtf.rtf");
        convert(engine, "epub", "rtf", epubFixture, epubToRtf);
        new BasicOutputValidator().validate(epubToRtf, MAX_OUTPUT_BYTES);
        String rtf = Files.readString(epubToRtf, StandardCharsets.UTF_8);
        assertThat(rtf).contains("MyHomeLib", "\\u1056");
        Path txtToEpub = tempDir.resolve("txt-to-epub.epub");
        convert(engine, "txt", "epub", txtFixture, txtToEpub);
        new EpubOutputValidator().validate(txtToEpub, MAX_OUTPUT_BYTES);

        System.out.printf("REAL_PANDOC_BRIDGE version=%s epubToTxt=%d docxToFb2=%d epubToRtf=%d txtToEpub=%d%n",
                tools.pandoc().version().raw(), Files.size(epubToTxt), Files.size(docxToFb2), Files.size(epubToRtf), Files.size(txtToEpub));
    }

    private void convert(PandocEngine engine, String sourceFormat, String targetFormat, Path source, Path target) throws Exception {
        ConversionStep step = PandocCapabilities.all().stream()
                .filter(candidate -> candidate.sourceFormat().equals(sourceFormat) && candidate.targetFormat().equals(targetFormat))
                .findFirst().orElseThrow();
        assertThat(Files.exists(target)).isFalse();
        engine.convert(step, source, target, () -> false, MAX_OUTPUT_BYTES);
        assertThat(target).isRegularFile();
    }

    private Path generateFixture(Path pandoc, Path source, String writer, Path target) throws Exception {
        Process process = new ProcessBuilder(pandoc.toString(), "--from", "markdown", "--to", writer,
                "--output", target.toString(), source.toString()).directory(tempDir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        assertThat(exit).as("Pandoc fixture generation (%s): %s", writer, output).isZero();
        assertThat(target).isRegularFile();
        return target;
    }

    private ApplicationSettingsPort settings(Map<String, String> initial) {
        return new ApplicationSettingsPort() {
            private final Map<String, String> values = new HashMap<>(initial);
            @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
            @Override public void put(String key, String value) { values.put(key, value); }
            @Override public void remove(String key) { values.remove(key); }
            @Override public Map<String, String> findByPrefix(String prefix) {
                Map<String, String> result = new HashMap<>();
                values.forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
                return result;
            }
        };
    }
}
