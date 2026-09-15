package com.myhomelibcorp.infrastructure.tts;

import com.myhomelibcorp.shared.util.ProcessExecutionSupport;

import com.myhomelibcorp.application.tts.TtsProvider;
import com.myhomelibcorp.application.tts.TtsVoice;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Uses the native speech stack already present on Windows/macOS/Linux. No bundled speech engine or cloud text upload. */
@Component
public class SystemTtsProvider implements TtsProvider {
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(5);
    private final Platform platform;
    private final CommandExecutor commands;
    private final AtomicReference<Process> current = new AtomicReference<>();
    private volatile List<TtsVoice> cachedVoices;

    public SystemTtsProvider() {
        this(Platform.detect(System.getProperty("os.name", "")), new ProcessCommandExecutor());
    }

    SystemTtsProvider(Platform platform, CommandExecutor commands) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    @Override public String id() { return "system"; }
    @Override public String displayName() { return "System TTS"; }

    @Override
    public boolean isAvailable() {
        return platform != Platform.UNSUPPORTED && commands.commandExists(platform.probeCommand());
    }

    @Override
    public List<TtsVoice> availableVoices() {
        List<TtsVoice> cached = cachedVoices;
        if (cached != null) return cached;
        if (!isAvailable()) return List.of();
        try {
            cached = switch (platform) {
                case WINDOWS -> parseWindowsVoices(commands.capture(windowsVoiceCommand()));
                case MAC -> parseMacVoices(commands.capture(List.of("say", "-v", "?")));
                case ESPEAK_NG -> parseEspeakVoices(commands.capture(List.of("espeak-ng", "--voices")));
                case ESPEAK -> parseEspeakVoices(commands.capture(List.of("espeak", "--voices")));
                case UNSUPPORTED -> List.of();
            };
        } catch (IOException failure) {
            cached = List.of();
        }
        cachedVoices = List.copyOf(cached);
        return cachedVoices;
    }

    @Override
    public void speak(String text, String voiceId, double rate) throws Exception {
        if (!isAvailable()) throw new IllegalStateException("System TTS is unavailable");
        String safeText = text == null ? "" : text;
        if (safeText.isBlank()) return;
        Process process = commands.start(speechCommand(voiceId, rate));
        current.set(process);
        try {
            try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(safeText);
            }
            long timeoutMs = COMMAND_TIMEOUT.toMillis();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("System TTS timed out");
            }
            if (process.exitValue() != 0) throw new IOException("System TTS exited with code " + process.exitValue());
        } finally {
            current.compareAndSet(process, null);
        }
    }

    @Override
    public void cancelCurrent() {
        Process process = current.getAndSet(null);
        if (process != null && process.isAlive()) process.destroyForcibly();
    }

    private List<String> speechCommand(String voiceId, double rate) {
        String voice = voiceId == null ? "" : voiceId.trim();
        int percent = (int) Math.round(Math.max(0.5, Math.min(2.0, rate)) * 100.0);
        return switch (platform) {
            case WINDOWS -> {
                String script = "$ErrorActionPreference='Stop'; Add-Type -AssemblyName System.Speech; "
                        + "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                        + "$v=$env:MHL_TTS_VOICE; if($v){$s.SelectVoice($v)}; "
                        + "$r=[int]$env:MHL_TTS_RATE; $s.Rate=[Math]::Max(-10,[Math]::Min(10,$r)); "
                        + "$t=[Console]::In.ReadToEnd(); $s.Speak($t);";
                yield List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", script,
                        "--mhl-voice=" + voice, "--mhl-rate=" + windowsRate(percent));
            }
            case MAC -> {
                List<String> cmd = new ArrayList<>(List.of("say"));
                if (!voice.isBlank()) { cmd.add("-v"); cmd.add(voice); }
                cmd.add("-r"); cmd.add(Integer.toString(Math.max(90, Math.min(420, (int) Math.round(190 * rate)))));
                yield List.copyOf(cmd);
            }
            case ESPEAK_NG, ESPEAK -> {
                List<String> cmd = new ArrayList<>();
                cmd.add(platform == Platform.ESPEAK_NG ? "espeak-ng" : "espeak");
                if (!voice.isBlank()) { cmd.add("-v"); cmd.add(voice); }
                cmd.add("-s"); cmd.add(Integer.toString(Math.max(80, Math.min(450, (int) Math.round(175 * rate)))));
                cmd.add("--stdin");
                yield List.copyOf(cmd);
            }
            case UNSUPPORTED -> throw new IllegalStateException("Unsupported TTS platform");
        };
    }

    private static int windowsRate(int percent) {
        double factor = percent / 100.0;
        return (int) Math.round((factor - 1.0) * 8.0);
    }

    static List<String> windowsVoiceCommand() {
        // Use PowerShell -EncodedCommand instead of an inline -Command script. Besides avoiding shell/quote
        // edge cases, this makes the script transport independent of the active Windows console code page.
        // The script itself emits only ASCII framing plus a UTF-8 Base64 voice name.
        String script = "$ErrorActionPreference='Stop'; "
                + "Add-Type -AssemblyName System.Speech; "
                + "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                + "foreach($installed in $s.GetInstalledVoices()){ "
                + "$v=$installed.VoiceInfo; "
                + "$name=[string]$v.Name; "
                + "$culture=if($null -ne $v.Culture){[string]$v.Culture.Name}else{''}; "
                + "$n=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($name)); "
                + "[Console]::Out.WriteLine(('MHLVOICE|' + $n + '|' + $culture)) "
                + "}";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return List.of("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
    }

    static List<TtsVoice> parseWindowsVoices(String output) {
        return lines(output).stream()
                .filter(line -> line.startsWith("MHLVOICE|"))
                .map(line -> line.split("\\|", 3))
                .filter(parts -> parts.length == 3)
                .map(parts -> new String[] { decodeWindowsVoiceName(parts[1]), parts[2].trim() })
                .filter(parts -> !parts[0].isBlank())
                .map(parts -> new TtsVoice(parts[0], parts[0], parts[1]))
                .toList();
    }

    private static String decodeWindowsVoiceName(String encoded) {
        if (encoded == null || encoded.isBlank()) return "";
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded.trim());
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException invalidBase64) {
            return ""; // malformed discovery row must never leak technical garbage into the voice chooser
        }
    }

    static List<TtsVoice> parseMacVoices(String output) {
        List<TtsVoice> result = new ArrayList<>();
        for (String line : lines(output)) {
            int marker = line.indexOf("#");
            String left = marker >= 0 ? line.substring(0, marker) : line;
            String[] tokens = left.trim().split("\\s+");
            if (tokens.length < 2) continue;
            String language = tokens[tokens.length - 1];
            String voice = left.substring(0, Math.max(0, left.lastIndexOf(language))).trim();
            if (!voice.isBlank()) result.add(new TtsVoice(voice, voice, language.replace('_', '-')));
        }
        return List.copyOf(result);
    }

    static List<TtsVoice> parseEspeakVoices(String output) {
        List<TtsVoice> result = new ArrayList<>();
        boolean headerSeen = false;
        for (String line : lines(output)) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;
            if (!headerSeen && trimmed.toLowerCase(Locale.ROOT).startsWith("pty")) { headerSeen = true; continue; }
            String[] tokens = trimmed.split("\\s+");
            if (tokens.length < 4 || !tokens[0].matches("\\d+")) continue;
            String language = tokens[1].replace('_', '-');
            String name = tokens[3];
            result.add(new TtsVoice(name, name, language));
        }
        return List.copyOf(result);
    }

    private static List<String> lines(String value) {
        if (value == null || value.isBlank()) return List.of();
        return value.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
    }

    enum Platform {
        WINDOWS, MAC, ESPEAK_NG, ESPEAK, UNSUPPORTED;
        static Platform detect(String osName) {
            String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
            if (os.contains("win")) return WINDOWS;
            if (os.contains("mac")) return MAC;
            // Linux preference is refined by command availability in the public constructor.
            if (os.contains("linux") || os.contains("unix")) {
                ProcessCommandExecutor probe = new ProcessCommandExecutor();
                if (probe.commandExists("espeak-ng")) return ESPEAK_NG;
                if (probe.commandExists("espeak")) return ESPEAK;
            }
            return UNSUPPORTED;
        }
        String probeCommand() {
            return switch (this) {
                case WINDOWS -> "powershell";
                case MAC -> "say";
                case ESPEAK_NG -> "espeak-ng";
                case ESPEAK -> "espeak";
                case UNSUPPORTED -> "";
            };
        }
    }

    interface CommandExecutor {
        boolean commandExists(String command);
        String capture(List<String> command) throws IOException;
        Process start(List<String> command) throws IOException;
    }

    static final class ProcessCommandExecutor implements CommandExecutor {
        @Override public boolean commandExists(String command) {
            if (command == null || command.isBlank()) return false;
            try {
                ProcessExecutionSupport.run(
                        List.of(command, versionArg(command)), null, java.time.Duration.ofSeconds(3), 16 * 1024);
                return true;
            } catch (Exception ignored) { return false; }
        }
        private String versionArg(String command) {
            String c = command.toLowerCase(Locale.ROOT);
            if (c.contains("powershell")) return "-Help";
            if (c.equals("say")) return "-h";
            return "--version";
        }
        @Override public String capture(List<String> command) throws IOException {
            try {
                ProcessExecutionSupport.Result result = ProcessExecutionSupport.run(
                        command, null, java.time.Duration.ofSeconds(15), 256 * 1024);
                if (result.exitCode() != 0) {
                    String diagnostic = result.stderrText().trim();
                    if (diagnostic.length() > 400) diagnostic = diagnostic.substring(0, 400);
                    throw new IOException("TTS discovery exited with code " + result.exitCode()
                            + (diagnostic.isBlank() ? "" : ": " + diagnostic));
                }
                return result.stdoutText();
            } catch (ProcessExecutionSupport.ProcessTimeoutException e) {
                throw new IOException("TTS discovery timed out", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
        }
        @Override public Process start(List<String> command) throws IOException {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (!command.isEmpty() && "powershell".equalsIgnoreCase(command.get(0))) {
                for (String token : command) {
                    if (token.startsWith("--mhl-voice=")) builder.environment().put("MHL_TTS_VOICE", token.substring("--mhl-voice=".length()));
                    if (token.startsWith("--mhl-rate=")) builder.environment().put("MHL_TTS_RATE", token.substring("--mhl-rate=".length()));
                }
                command = command.stream().filter(token -> !token.startsWith("--mhl-")).toList();
                builder.command(command);
            }
            // Speech output is not consumed by MyHomeLib. Discard both streams so a verbose
            // native TTS backend cannot block while speak() waits for process completion.
            return builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        }
    }
}
