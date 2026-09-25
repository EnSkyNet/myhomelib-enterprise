package com.myhomelibcorp.converter.toolchain.discovery;

import com.myhomelibcorp.converter.toolchain.ConversionToolchainException;
import com.myhomelibcorp.converter.toolchain.process.BoundedOutputCapture;
import com.myhomelibcorp.converter.toolchain.process.ExternalToolResult;
import com.myhomelibcorp.converter.toolchain.process.ExternalToolRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class ConverterToolProbe {
    private final ExternalToolRunner runner;

    public ConverterToolProbe(ExternalToolRunner runner) { this.runner = runner; }

    public ToolInstallation probe(String id, Path executable, List<String> versionArgs) {
        if (executable == null) return ToolInstallation.unavailable(id, "Executable not found");
        List<String> argv = new ArrayList<>();
        argv.add(executable.toString());
        argv.addAll(versionArgs == null ? List.of() : versionArgs);
        try {
            ExternalToolResult result = runner.probe(argv,
                    executable.toAbsolutePath().normalize().getParent(), Duration.ofSeconds(10));
            String text = result.stdout().isBlank() ? result.stderr() : result.stdout();
            if (result.exitCode() != 0) {
                return ToolInstallation.unavailable(id, "Version probe exited with code " + result.exitCode());
            }
            String versionLine = expectedVersionLine(id, text);
            if (versionLine.isBlank()) {
                return ToolInstallation.unavailable(id, "Unexpected version probe output");
            }
            String compatibilityDiagnostic = compatibilityDiagnostic(id, versionLine);
            if (!compatibilityDiagnostic.isBlank()) {
                return new ToolInstallation(id, executable, new ToolVersion(versionLine), false, compatibilityDiagnostic);
            }
            String runtimeDiagnostic = runtimeLayoutDiagnostic(id, executable);
            if (!runtimeDiagnostic.isBlank()) {
                return new ToolInstallation(id, executable, new ToolVersion(versionLine), false, runtimeDiagnostic);
            }
            return new ToolInstallation(id, executable, new ToolVersion(versionLine), true, "");
        } catch (ConversionToolchainException e) {
            return ToolInstallation.unavailable(id, BoundedOutputCapture.compact(e.getMessage()));
        }
    }

    private boolean matchesExpectedVersionSignature(String id, String versionLine) {
        if (versionLine == null || versionLine.isBlank()) return false;
        String lower = versionLine.toLowerCase(java.util.Locale.ROOT);
        return switch (id == null ? "" : id.toLowerCase(java.util.Locale.ROOT)) {
            case "fbc" -> lower.contains("fbc") || lower.contains("fb2cng");
            case "pandoc" -> lower.startsWith("pandoc ") || lower.equals("pandoc");
            case "libmobi" -> lower.startsWith("libmobi:") || lower.startsWith("libmobi ");
            case "calibre" -> lower.contains("calibre")
                    && (lower.contains("ebook-convert") || lower.contains("ebook convert"));
            default -> false;
        };
    }


    private String compatibilityDiagnostic(String id, String versionLine) {
        if (!"pandoc".equalsIgnoreCase(id == null ? "" : id)) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)^pandoc\\s+(\\d+)(?:\\.(\\d+))?.*$")
                .matcher(versionLine == null ? "" : versionLine.strip());
        if (!matcher.matches()) return "Pandoc version cannot be determined; Pandoc 3.11+ is required";
        int major;
        int minor;
        try {
            major = Integer.parseInt(matcher.group(1));
            minor = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException invalid) {
            return "Pandoc version cannot be determined; Pandoc 3.11+ is required";
        }
        if (major < 3 || major == 3 && minor < 11) {
            return "Pandoc 3.11+ is required for the mandatory --sandbox document bridge; detected " + versionLine;
        }
        return "";
    }

    private String runtimeLayoutDiagnostic(String id, Path executable) {
        if (!"calibre".equalsIgnoreCase(id == null ? "" : id) || executable == null) return "";
        Path fileName = executable.getFileName();
        if (fileName == null || !fileName.toString().equalsIgnoreCase("ebook-convert.exe")) return "";
        Path parent = executable.getParent();
        if (parent == null) return "Calibre installation directory cannot be determined";
        Path worker = parent.resolve("calibre-parallel.exe");
        if (!Files.isRegularFile(worker, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return "Calibre is incomplete: required calibre-parallel.exe is missing next to ebook-convert.exe";
        }
        return "";
    }

    private String expectedVersionLine(String id, String text) {
        if (text == null) return "";
        return text.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .filter(line -> matchesExpectedVersionSignature(id, line))
                .findFirst()
                .orElse("");
    }
}
