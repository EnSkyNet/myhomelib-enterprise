package com.myhomelibcorp.infrastructure.tts;

import com.myhomelibcorp.application.tts.TtsVoice;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemTtsProviderTest {
    @Test
    void parsesOnlyFramedWindowsVoicesWithCulture() {
        String david = Base64.getEncoder().encodeToString("Microsoft David Desktop".getBytes(StandardCharsets.UTF_8));
        String olena = Base64.getEncoder().encodeToString("Олена".getBytes(StandardCharsets.UTF_8));

        assertThat(SystemTtsProvider.parseWindowsVoices(
                "MHLVOICE|" + david + "|en-US\nMHLVOICE|" + olena + "|uk-UA\n"))
                .extracting(TtsVoice::id, TtsVoice::languageTag)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Microsoft David Desktop", "en-US"),
                        org.assertj.core.groups.Tuple.tuple("Олена", "uk-UA"));
    }

    @Test
    void windowsVoiceDiscoveryUsesEncodedCommandAndAsciiSafeFraming() {
        List<String> command = SystemTtsProvider.windowsVoiceCommand();
        assertThat(command).contains("-EncodedCommand").doesNotContain("-Command");

        String encoded = command.get(command.size() - 1);
        String script = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_16LE);
        assertThat(script)
                .contains("GetInstalledVoices")
                .contains("ToBase64String")
                .contains("Encoding]::UTF8")
                .contains("MHLVOICE|")
                .contains("$ErrorActionPreference='Stop'");
    }

    @Test
    void parsesWindowsVoiceNamesThroughAsciiSafeUtf8Base64Transport() {
        String ukrainian = Base64.getEncoder().encodeToString("Microsoft Олена".getBytes(StandardCharsets.UTF_8));
        String russian = Base64.getEncoder().encodeToString("Microsoft Ирина".getBytes(StandardCharsets.UTF_8));

        assertThat(SystemTtsProvider.parseWindowsVoices(
                "MHLVOICE|" + ukrainian + "|uk-UA\nMHLVOICE|" + russian + "|ru-RU\n"))
                .extracting(TtsVoice::displayName, TtsVoice::languageTag)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Microsoft Олена", "uk-UA"),
                        org.assertj.core.groups.Tuple.tuple("Microsoft Ирина", "ru-RU"));
    }

    @Test
    void powershellDiagnosticsAndMalformedRowsNeverBecomeVoices() {
        String valid = Base64.getEncoder().encodeToString("Microsoft Олена".getBytes(StandardCharsets.UTF_8));
        String output = "ParserError: ExpectedValueExpression\n"
                + "FullyQualifiedErrorId : ExpectedValueExpression\n"
                + "... | ForEach-Object { $v=$_.VoiceInfo; Write-Output (...) }\n"
                + "MHLVOICE|%%%not-base64%%%|uk-UA\n"
                + "MHLVOICE|" + valid + "|uk-UA\n";

        assertThat(SystemTtsProvider.parseWindowsVoices(output))
                .extracting(TtsVoice::displayName, TtsVoice::languageTag)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Microsoft Олена", "uk-UA"));
    }

    @Test
    void processCaptureFailsClosedOnNonZeroExitInsteadOfReturningStderrAsVoiceData() {
        SystemTtsProvider.ProcessCommandExecutor executor = new SystemTtsProvider.ProcessCommandExecutor();
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();

        assertThatThrownBy(() -> executor.capture(List.of(java, "-definitely-not-a-valid-java-option")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("TTS discovery exited with code");
    }

    @Test
    void parsesMacAndEspeakVoiceListings() {
        assertThat(SystemTtsProvider.parseMacVoices("Samantha              en_US    # Hello\nOksana                uk_UA    # Привіт\n"))
                .extracting(TtsVoice::id, TtsVoice::languageTag)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Samantha", "en-US"),
                        org.assertj.core.groups.Tuple.tuple("Oksana", "uk-UA"));

        String espeak = "Pty Language Age/Gender VoiceName File Other Languages\n"
                + " 5  en-us          M  english-us       en-us\n"
                + " 5  uk             M  ukrainian        eu/uk\n";
        assertThat(SystemTtsProvider.parseEspeakVoices(espeak))
                .extracting(TtsVoice::id, TtsVoice::languageTag)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("english-us", "en-us"),
                        org.assertj.core.groups.Tuple.tuple("ukrainian", "uk"));
    }

    @Test
    void cachesVoiceDiscoveryAndReportsUnavailablePlatformWithoutExecutingCommands() {
        FakeCommands commands = new FakeCommands();
        SystemTtsProvider provider = new SystemTtsProvider(SystemTtsProvider.Platform.WINDOWS, commands);

        assertThat(provider.availableVoices()).hasSize(1);
        assertThat(provider.availableVoices()).hasSize(1);
        assertThat(commands.captureCalls).hasValue(1);

        SystemTtsProvider unsupported = new SystemTtsProvider(SystemTtsProvider.Platform.UNSUPPORTED, commands);
        assertThat(unsupported.isAvailable()).isFalse();
    }

    private static final class FakeCommands implements SystemTtsProvider.CommandExecutor {
        private final AtomicInteger captureCalls = new AtomicInteger();
        @Override public boolean commandExists(String command) { return true; }
        @Override public String capture(List<String> command) throws IOException {
            captureCalls.incrementAndGet();
            String name = Base64.getEncoder().encodeToString("Test Voice".getBytes(StandardCharsets.UTF_8));
            return "MHLVOICE|" + name + "|uk-UA\n";
        }
        @Override public Process start(List<String> command) { throw new UnsupportedOperationException(); }
    }
}
