package com.myhomelibcorp.converter.toolchain.discovery;

import com.myhomelibcorp.converter.toolchain.process.ExternalToolResult;
import com.myhomelibcorp.converter.toolchain.process.ExternalToolRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConverterToolProbeTest {
    @Test
    void acceptsExpectedVersionSignatures() {
        assertThat(probe("fbc", "fbc version 1.2.3").available()).isTrue();
        assertThat(probe("pandoc", "pandoc 3.11").available()).isTrue();
        assertThat(probe("libmobi", "libmobi: 0.12").available()).isTrue();
    }



    @Test
    void acceptsRealLibmobiVersionOutputWhenBuildBannerComesFirst() {
        ToolInstallation installation = probe("libmobi",
                "mobitool build: Sep 21 2026 12:11:37 (gcc 14.2.0)\nlibmobi: 0.12");

        assertThat(installation.available()).isTrue();
        assertThat(installation.version().raw()).isEqualTo("libmobi: 0.12");
    }

    @Test
    void rejectsPandocOlderThanRequiredSandboxBridgeVersion() {
        ToolInstallation installation = probe("pandoc", "pandoc 3.1.11.1");
        assertThat(installation.available()).isFalse();
        assertThat(installation.executable()).isNotNull();
        assertThat(installation.version().raw()).isEqualTo("pandoc 3.1.11.1");
        assertThat(installation.diagnostic()).contains("Pandoc 3.11+").contains("--sandbox");
    }

    @Test
    void acceptsPandocAtOrAboveRequiredVersion() {
        assertThat(probe("pandoc", "pandoc 3.11.0").available()).isTrue();
        assertThat(probe("pandoc", "pandoc 3.12").available()).isTrue();
        assertThat(probe("pandoc", "pandoc 4.0").available()).isTrue();
    }

    @Test
    void rejectsIncompleteWindowsCalibreLayoutEvenWhenVersionProbeSucceeds(@TempDir Path dir) throws Exception {
        Path executable = Files.createFile(dir.resolve("ebook-convert.exe"));
        ExternalToolRunner runner = new ExternalToolRunner() {
            @Override
            public ExternalToolResult probe(List<String> argv, Path workingDirectory, Duration timeout) {
                return new ExternalToolResult(0, "ebook-convert.exe (calibre 8.10.0)", "", false, false, 1);
            }
        };

        ToolInstallation installation = new ConverterToolProbe(runner)
                .probe("calibre", executable, List.of("--version"));

        assertThat(installation.available()).isFalse();
        assertThat(installation.diagnostic()).contains("calibre-parallel.exe");
    }

    @Test
    void rejectsUnrelatedExecutableOutput() {
        ToolInstallation installation = probe("pandoc", "Python 3.13.1");
        assertThat(installation.available()).isFalse();
        assertThat(installation.diagnostic()).contains("Unexpected version probe output");
    }

    private ToolInstallation probe(String id, String output) {
        ExternalToolRunner runner = new ExternalToolRunner() {
            @Override
            public ExternalToolResult probe(List<String> argv, Path workingDirectory, Duration timeout) {
                return new ExternalToolResult(0, output, "", false, false, 1);
            }
        };
        return new ConverterToolProbe(runner).probe(id, Path.of("/tmp/fake-tool"), List.of("--version"));
    }
}
