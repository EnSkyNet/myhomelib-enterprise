package com.myhomelibcorp.converter.toolchain.process;

import com.myhomelibcorp.converter.toolchain.ConversionFailureKind;
import com.myhomelibcorp.converter.toolchain.ConversionToolchainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalToolRunnerTest {
    @TempDir Path tempDir;
    private final ExternalToolRunner runner = new ExternalToolRunner();

    @Test
    void classifiesOversizedMonitoredOutputAsOutputInvalid() throws Exception {
        Path output = tempDir.resolve("output.epub");
        Files.write(output, new byte[32]);

        assertThatThrownBy(() -> runner.run(request(output, 16)))
                .isInstanceOf(ConversionToolchainException.class)
                .satisfies(error -> assertThat(((ConversionToolchainException) error).kind())
                        .isEqualTo(ConversionFailureKind.OUTPUT_INVALID));
    }

    @Test
    void rejectsSymlinkMonitoredOutputWhenPlatformSupportsIt() throws Exception {
        Path real = tempDir.resolve("real.epub");
        Files.writeString(real, "x");
        Path output = tempDir.resolve("output.epub");
        try {
            Files.createSymbolicLink(output, real.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }

        assertThatThrownBy(() -> runner.run(request(output, 1024)))
                .isInstanceOf(ConversionToolchainException.class)
                .satisfies(error -> assertThat(((ConversionToolchainException) error).kind())
                        .isEqualTo(ConversionFailureKind.SECURITY_REJECTED));
    }

    @Test
    void rejectsSymlinkWorkingDirectoryWhenPlatformSupportsIt() throws Exception {
        Path real = Files.createDirectory(tempDir.resolve("real-work"));
        Path link = tempDir.resolve("work-link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }
        ExternalToolRequest request = new ExternalToolRequest(
                javaCommand(), link, Duration.ofSeconds(10), 4096, () -> false, null, Long.MAX_VALUE);

        assertThatThrownBy(() -> runner.run(request))
                .isInstanceOf(ConversionToolchainException.class)
                .satisfies(error -> assertThat(((ConversionToolchainException) error).kind())
                        .isEqualTo(ConversionFailureKind.SECURITY_REJECTED));
    }

    @Test
    void mapsCallerCancellationToCancelled() {
        ExternalToolRequest request = new ExternalToolRequest(
                javaCommand(), tempDir, Duration.ofSeconds(10), 4096,
                () -> true, null, Long.MAX_VALUE);

        assertThatThrownBy(() -> runner.run(request))
                .isInstanceOf(ConversionToolchainException.class)
                .satisfies(error -> assertThat(((ConversionToolchainException) error).kind())
                        .isEqualTo(ConversionFailureKind.CANCELLED));
    }

    private ExternalToolRequest request(Path monitoredOutput, long maxOutputBytes) {
        return new ExternalToolRequest(javaCommand(), tempDir, Duration.ofSeconds(10), 4096,
                () -> false, monitoredOutput, maxOutputBytes);
    }

    private List<String> javaCommand() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        return List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                ProcessFixtureMain.class.getName(), "5000");
    }
}
