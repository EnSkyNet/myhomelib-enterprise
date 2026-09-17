package com.myhomelibcorp.plugins.calibre;

import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.application.conversion.BookConversionContext;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.plugin.api.BookConverter;
import com.myhomelibcorp.plugin.api.PluginApiRange;
import com.myhomelibcorp.plugin.api.PluginApiVersion;
import com.myhomelibcorp.plugin.api.PluginEntrypoint;
import com.myhomelibcorp.plugin.api.PluginManifest;
import com.myhomelibcorp.plugin.api.PluginPermission;
import com.myhomelibcorp.plugin.api.PluginService;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Installable plugin bridge to calibre's separately installed ebook-convert executable. */
public final class CalibreConverterPlugin implements PluginEntrypoint {
    private static final CalibreConverter CONVERTER = new CalibreConverter();

    @Override
    public PluginManifest manifest() {
        return new PluginManifest(
                "converter.calibre", "Calibre — конвертація книг", "1.0.0",
                new PluginApiRange(new PluginApiVersion(1, 4), new PluginApiVersion(1, 99)),
                Set.of(PluginService.BOOK_CONVERTER), Set.of(),
                Set.of(PluginPermission.FILESYSTEM_READ, PluginPermission.FILESYSTEM_WRITE,
                        PluginPermission.EXTERNAL_PROCESS_EXECUTION));
    }

    @Override
    public Map<PluginService, Object> services() {
        return Map.of(PluginService.BOOK_CONVERTER, CONVERTER);
    }

    static final class CalibreConverter implements BookConverter {
        private static final Set<String> SOURCES = Set.of(
                "azw", "azw3", "azw4", "cbz", "cbr", "cb7", "cbc", "chm", "djvu", "docx",
                "epub", "fb2", "fb2_zip", "fbz", "html", "htm", "htmlz", "kepub", "lit", "lrf",
                "mobi", "odt", "pdf", "prc", "pdb", "pml", "rb", "rtf", "snb", "tcr", "txt", "txtz");
        private static final List<Target> TARGETS = List.of(
                new Target("azw3", ".azw3"), new Target("epub", ".epub"), new Target("docx", ".docx"),
                new Target("fb2", ".fb2"), new Target("htmlz", ".htmlz"), new Target("kepub", ".kepub"),
                new Target("lit", ".lit"), new Target("lrf", ".lrf"), new Target("mobi", ".mobi"),
                new Target("pdb", ".pdb"), new Target("pmlz", ".pmlz"), new Target("rb", ".rb"),
                new Target("pdf", ".pdf"), new Target("rtf", ".rtf"), new Target("snb", ".snb"),
                new Target("tcr", ".tcr"), new Target("txt", ".txt"), new Target("txtz", ".txtz"),
                new Target("zip", ".zip"));
        private static final String EXECUTABLE_PROPERTY = "myhomelib.calibre.executable";
        private static final String TIMEOUT_PROPERTY = "myhomelib.calibre.timeoutSeconds";
        private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
        private static final int MAX_CAPTURE_BYTES = 64 * 1024;
        private static final int MAX_FAILURE_TEXT = 2_000;

        @Override public String id() { return "plugin:calibre-cli"; }
        @Override public boolean isAvailable() { return executable().isPresent(); }
        @Override public boolean supports(Book book) { return SOURCES.contains(sourceFormat(book)); }
        @Override public boolean supports(Book book, String sourceFormat) {
            return SOURCES.contains(BookConversionCapability.normalizeFormat(sourceFormat));
        }
        @Override public String getTargetExtension() { return ".epub"; }
        @Override public String getFormatName() { return "CALIBRE"; }

        @Override
        public Set<BookConversionCapability> capabilities() {
            Set<BookConversionCapability> result = new LinkedHashSet<>();
            for (Target target : TARGETS) result.add(new BookConversionCapability(SOURCES, target.format(), target.extension()));
            return Set.copyOf(result);
        }

        @Override
        public void convert(BookConversionContext context) throws Exception {
            run(context.sourceFormat(), context.sourceStream(), context.targetFormat(), context.targetFile(), context.cancelled());
        }

        @Override
        public void convert(Book book, InputStream sourceStream, Path targetFile) throws Exception {
            String target = extensionFormat(targetFile);
            run(sourceFormat(book), sourceStream, target, targetFile, () -> false);
        }

        private void run(String sourceFormat, InputStream input, String targetFormat, Path targetFile,
                         BooleanSupplier cancelled) throws Exception {
            Path exe = executable().orElseThrow(() -> new IllegalStateException(
                    "calibre ebook-convert не знайдено. Встановіть calibre або додайте ebook-convert до PATH."));
            String source = BookConversionCapability.normalizeFormat(sourceFormat);
            String target = BookConversionCapability.normalizeFormat(targetFormat);
            if (!SOURCES.contains(source)) throw new IllegalArgumentException("Непідтримуваний вхідний формат: " + source);
            if (TARGETS.stream().noneMatch(value -> value.format().equals(target))) {
                throw new IllegalArgumentException("Непідтримуваний формат результату: " + target);
            }
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("Конвертацію скасовано");

            Path work = Files.createTempDirectory("mhl-calibre-plugin-");
            Path sourceFile = work.resolve("source" + sourceExtension(source));
            Path out = targetFile.toAbsolutePath().normalize();
            try {
                Files.copy(input, sourceFile, StandardCopyOption.REPLACE_EXISTING);
                Files.createDirectories(out.getParent());
                Files.deleteIfExists(out);
                Process process = new ProcessBuilder(exe.toString(), sourceFile.toString(), out.toString())
                        .directory(work.toFile()).redirectErrorStream(true).start();
                ByteArrayOutputStream captured = new ByteArrayOutputStream();
                Thread reader = Thread.ofVirtual().start(() -> {
                    try (InputStream stdout = process.getInputStream()) {
                        byte[] buffer = new byte[8 * 1024];
                        int read;
                        int retained = 0;
                        while ((read = stdout.read(buffer)) >= 0) {
                            if (read <= 0 || retained >= MAX_CAPTURE_BYTES) continue;
                            int keep = Math.min(read, MAX_CAPTURE_BYTES - retained);
                            captured.write(buffer, 0, keep);
                            retained += keep;
                        }
                    } catch (Exception ignored) { }
                });
                Duration timeout = configuredTimeout();
                long deadline = System.nanoTime() + timeout.toNanos();
                while (process.isAlive()) {
                    if (cancelled.getAsBoolean()) {
                        process.destroyForcibly();
                        throw new java.util.concurrent.CancellationException("Конвертацію скасовано");
                    }
                    if (System.nanoTime() > deadline) {
                        process.destroyForcibly();
                        throw new IllegalStateException("calibre перевищив ліміт часу " + timeout.toSeconds() + " с");
                    }
                    process.waitFor(100, TimeUnit.MILLISECONDS);
                }
                reader.join(1000);
                if (process.exitValue() != 0) {
                    String text = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
                    if (text.length() > MAX_FAILURE_TEXT) text = text.substring(0, MAX_FAILURE_TEXT);
                    throw new IllegalStateException("calibre завершився з кодом " + process.exitValue()
                            + (text.isBlank() ? "" : ": " + text.strip()));
                }
                if (!Files.isRegularFile(out) || Files.size(out) <= 0) {
                    throw new IllegalStateException("calibre не створив коректний файл результату");
                }
            } finally {
                try (var paths = Files.walk(work)) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) { } });
                }
            }
        }

        private static Optional<Path> executable() {
            String configured = stripOuterQuotes(System.getProperty(EXECUTABLE_PROPERTY, "").trim());
            if (!configured.isBlank()) {
                try {
                    Path candidate = Path.of(configured);
                    if (!candidate.isAbsolute() && !configured.contains("/") && !configured.contains("\\")) {
                        Optional<Path> onPath = findOnPath(configured);
                        if (onPath.isPresent()) return onPath;
                    } else {
                        candidate = candidate.toAbsolutePath().normalize();
                        if (validExecutable(candidate)) return Optional.of(candidate);
                    }
                } catch (RuntimeException ignored) {
                    // Invalid explicit path is treated as unavailable, then normal discovery continues.
                }
            }
            String exe = isWindows() ? "ebook-convert.exe" : "ebook-convert";
            Optional<Path> onPath = findOnPath(exe);
            if (onPath.isPresent()) return onPath;
            if (isWindows()) {
                for (String env : List.of("ProgramFiles", "ProgramFiles(x86)")) {
                    String base = System.getenv(env);
                    if (base != null && !base.isBlank()) {
                        Path candidate = Path.of(base, "Calibre2", "ebook-convert.exe");
                        if (validExecutable(candidate)) return Optional.of(candidate);
                    }
                }
            } else if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
                Path candidate = Path.of("/Applications/calibre.app/Contents/MacOS/ebook-convert");
                if (validExecutable(candidate)) return Optional.of(candidate);
            }
            return Optional.empty();
        }

        private static Optional<Path> findOnPath(String executable) {
            String path = System.getenv("PATH");
            if (path == null || path.isBlank()) return Optional.empty();
            for (String part : path.split(java.io.File.pathSeparator)) {
                if (part == null || part.isBlank()) continue;
                try {
                    Path candidate = Path.of(part).resolve(executable).toAbsolutePath().normalize();
                    if (validExecutable(candidate)) return Optional.of(candidate);
                } catch (RuntimeException ignored) { }
            }
            return Optional.empty();
        }

        private static Duration configuredTimeout() {
            try {
                long seconds = Long.parseLong(System.getProperty(TIMEOUT_PROPERTY, Long.toString(DEFAULT_TIMEOUT.toSeconds())).trim());
                return Duration.ofSeconds(Math.max(10L, Math.min(3600L, seconds)));
            } catch (RuntimeException invalid) {
                return DEFAULT_TIMEOUT;
            }
        }

        private static String stripOuterQuotes(String value) {
            if (value == null) return "";
            String clean = value.trim();
            if (clean.length() >= 2 && ((clean.startsWith("\"") && clean.endsWith("\""))
                    || (clean.startsWith("'") && clean.endsWith("'")))) {
                return clean.substring(1, clean.length() - 1).trim();
            }
            return clean;
        }

        private static boolean validExecutable(Path path) {
            return Files.isRegularFile(path) && (isWindows() || Files.isExecutable(path));
        }

        private static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        }

        private static String sourceFormat(Book book) {
            String name = book == null ? "" : book.getArchiveEntry();
            if (name == null || name.isBlank()) name = book == null ? "" : book.getFileName();
            if (name == null) return "";
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".fb2.zip")) return "fb2_zip";
            int dot = lower.lastIndexOf('.');
            return dot >= 0 ? BookConversionCapability.normalizeFormat(lower.substring(dot + 1)) : "";
        }

        private static String sourceExtension(String source) {
            return "fb2_zip".equals(source) ? ".fbz" : "." + source;
        }

        private static String extensionFormat(Path path) {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".fb2.zip")) return "fb2_zip";
            int dot = name.lastIndexOf('.');
            return dot >= 0 ? BookConversionCapability.normalizeFormat(name.substring(dot + 1)) : "";
        }

        private record Target(String format, String extension) { }
    }
}
