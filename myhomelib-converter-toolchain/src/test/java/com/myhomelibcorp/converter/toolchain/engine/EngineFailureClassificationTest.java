package com.myhomelibcorp.converter.toolchain.engine;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.converter.toolchain.*;
import com.myhomelibcorp.converter.toolchain.discovery.ConverterToolRegistry;
import com.myhomelibcorp.converter.toolchain.discovery.ToolInstallation;
import com.myhomelibcorp.converter.toolchain.discovery.ToolVersion;
import com.myhomelibcorp.converter.toolchain.engine.fbc.FbcEngine;
import com.myhomelibcorp.converter.toolchain.engine.libmobi.LibMobiEngine;
import com.myhomelibcorp.converter.toolchain.engine.pandoc.PandocEngine;
import com.myhomelibcorp.converter.toolchain.process.ExternalToolResult;
import com.myhomelibcorp.converter.toolchain.process.ExternalToolRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineFailureClassificationTest {
    @TempDir Path tempDir;

    @Test
    void pandocUnsupportedWriterAllowsProviderFallbackSignal() throws Exception {
        assertFailure(new PandocEngine(registry("pandoc"), runner("Unknown writer: x"), settings()),
                step(ConversionEngineId.PANDOC, "epub", "fb2", ".fb2"),
                "book.epub", ConversionFailureKind.UNSUPPORTED);
    }

    @Test
    void pandocParseFailureIsHardInvalidInput() throws Exception {
        assertFailure(new PandocEngine(registry("pandoc"), runner("Error parsing input"), settings()),
                step(ConversionEngineId.PANDOC, "epub", "fb2", ".fb2"),
                "book.epub", ConversionFailureKind.INVALID_INPUT);
    }

    @Test
    void libmobiEncryptedInputIsSecurityRejected() throws Exception {
        assertFailure(new LibMobiEngine(registry("libmobi"), runner("Document is encrypted"), settings()),
                step(ConversionEngineId.LIBMOBI, "mobi", "epub", ".epub"),
                "book.mobi", ConversionFailureKind.SECURITY_REJECTED);
    }

    @Test
    void fbcUnsupportedOutputAllowsProviderFallbackSignal() throws Exception {
        assertFailure(new FbcEngine(registry("fbc"), runner("unsupported output format"), settings()),
                step(ConversionEngineId.FBC, "fb2", "epub", ".epub"),
                "book.fb2", ConversionFailureKind.UNSUPPORTED);
    }

    private void assertFailure(ConversionEngine engine, ConversionStep step, String sourceName,
                               ConversionFailureKind expected) throws Exception {
        Path source = tempDir.resolve(sourceName);
        Files.writeString(source, "source");
        Path target = tempDir.resolve("out" + step.targetExtension());
        assertThatThrownBy(() -> engine.convert(step, source, target, () -> false, 1024 * 1024))
                .isInstanceOf(ConversionToolchainException.class)
                .satisfies(error -> assertThat(((ConversionToolchainException) error).kind()).isEqualTo(expected));
    }

    private ExternalToolRunner runner(String stderr) {
        return new ExternalToolRunner() {
            @Override
            public com.myhomelibcorp.converter.toolchain.process.ExternalToolResult run(
                    com.myhomelibcorp.converter.toolchain.process.ExternalToolRequest request) {
                return new ExternalToolResult(2, "", stderr, false, false, 1);
            }
        };
    }

    private ConverterToolRegistry registry(String id) {
        ToolInstallation installation = new ToolInstallation(id, tempDir.resolve(id), new ToolVersion("test"), true, "");
        return new ConverterToolRegistry(null, null) {
            @Override public ToolInstallation fbc() { return "fbc".equals(id) ? installation : ToolInstallation.unavailable("fbc", ""); }
            @Override public ToolInstallation pandoc() { return "pandoc".equals(id) ? installation : ToolInstallation.unavailable("pandoc", ""); }
            @Override public ToolInstallation libmobi() { return "libmobi".equals(id) ? installation : ToolInstallation.unavailable("libmobi", ""); }
        };
    }

    private ConversionStep step(ConversionEngineId id, String source, String target, String extension) {
        return new ConversionStep(id, source, target, extension, 100, false, 1);
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
