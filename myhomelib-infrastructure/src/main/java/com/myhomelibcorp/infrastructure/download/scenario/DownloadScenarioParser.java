package com.myhomelibcorp.infrastructure.download.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.net.URI;

/**
 * Strict, declarative parser. It never evaluates code or invokes a shell/runtime.
 */
public final class DownloadScenarioParser {
    private DownloadScenarioParser() { }

    public static List<DownloadScenarioCommand> parse(String script) throws DownloadScenarioException {
        if (script == null || script.isBlank()) return List.of();
        String normalized = script.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        List<DownloadScenarioCommand> result = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i].trim();
            if (raw.isEmpty()) continue;
            // Legacy MyHomeLib ConnectionScript files may start with a bare HTTP(S) URL.
            // Delphi 2.5 parsed such an unknown line with Code=-1 and simply skipped it.
            // Preserve only this narrow compatibility case; arbitrary unknown commands remain errors.
            if (isLegacyUrlPreamble(raw)) continue;
            int split = firstWhitespace(raw);
            String token = (split < 0 ? raw : raw.substring(0, split)).toUpperCase(Locale.ROOT);
            String args = split < 0 ? "" : raw.substring(split).trim();
            DownloadScenarioCommand.Type type;
            try { type = DownloadScenarioCommand.Type.valueOf(token); }
            catch (IllegalArgumentException e) {
                throw error(i, "невідома команда " + token);
            }
            switch (type) {
                case CHECK, REDIR -> {
                    if (!args.isEmpty()) throw error(i, token + " не приймає параметрів");
                    result.add(new DownloadScenarioCommand(type, null, null, i + 1));
                }
                case PAUSE -> {
                    if (args.isEmpty()) throw error(i, "PAUSE потребує milliseconds");
                    try {
                        long ms = Long.parseLong(args);
                        if (ms < 0 || ms > 60_000) throw error(i, "PAUSE поза безпечним діапазоном 0..60000 ms");
                    } catch (NumberFormatException e) {
                        throw error(i, "PAUSE має містити ціле число milliseconds");
                    }
                    result.add(new DownloadScenarioCommand(type, args, null, i + 1));
                }
                case GET, POST -> {
                    if (args.isEmpty()) throw error(i, token + " потребує URL");
                    rejectControlChars(args, i);
                    result.add(new DownloadScenarioCommand(type, args, null, i + 1));
                }
                case ADD -> {
                    if (args.isEmpty()) throw error(i, "ADD потребує name і value");
                    int p = firstWhitespace(args);
                    if (p < 1 || p == args.length() - 1) throw error(i, "ADD потребує name і value");
                    String name = args.substring(0, p).trim();
                    String value = args.substring(p).trim();
                    if (!name.matches("[A-Za-z0-9_.\\-]+")) throw error(i, "небезпечне ім'я ADD parameter");
                    rejectControlChars(value, i);
                    result.add(new DownloadScenarioCommand(type, name, value, i + 1));
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Перевіряє, чи містить сценарій команди GET або POST.
     * Використовується для швидкого визначення режиму завантаження.
     */
    public static boolean hasNetworkRequestCommand(String script) {
        if (script == null || script.isBlank()) {
            return false;
        }
        String normalized = script.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        for (String raw : lines) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) continue;
            int split = firstWhitespace(trimmed);
            if (split < 0) continue;
            String token = trimmed.substring(0, split).toUpperCase(Locale.ROOT);
            if ("GET".equals(token) || "POST".equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLegacyUrlPreamble(String raw) {
        if (firstWhitespace(raw) >= 0) return false;
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme();
            return uri.getHost() != null && scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static int firstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i))) return i;
        return -1;
    }

    private static void rejectControlChars(String value, int line) throws DownloadScenarioException {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
            throw error(line, "control characters заборонені");
        }
    }

    private static DownloadScenarioException error(int zeroBasedLine, String message) {
        return new DownloadScenarioException("ConnectionScript, рядок " + (zeroBasedLine + 1) + ": " + message);
    }
}