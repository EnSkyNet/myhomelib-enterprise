package com.myhomelibcorp.updater;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.*;

/**
 * Minimal standalone updater. It deliberately depends only on the JDK so the helper can run from
 * its own JAR while the main MyHomeLib bootstrap JAR is closed and replaceable on Windows.
 */
public final class ApplicationUpdaterMain {
    static final String HEALTH_ENV = "MYHOMELIB_UPDATE_HEALTH_MARKER";
    private static final String PRODUCT = "myhomelib";

    ApplicationUpdaterMain() { }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            if (options.parentPid() > 0) waitForParent(options.parentPid(), Duration.ofMinutes(2));
            Plan plan = Plan.load(options.plan());
            new ApplicationUpdaterMain().apply(plan, options, loadKeys());
        } catch (Throwable failure) {
            System.err.println("MYHOMELIB_UPDATE_FAILED: " + failure.getMessage());
            failure.printStackTrace(System.err);
            System.exit(2);
        }
    }

    Result apply(Plan plan, Options options, Map<String, PublicKey> trust) throws Exception {
        verifyManifest(plan, options, trust);
        verifyArtifact(plan.stagedArtifact(), plan.artifactLength(), plan.sha256());

        Path install = requireRegularNoLinks(plan.installationPath(), "installation");
        Path staged = requireRegularNoLinks(plan.stagedArtifact(), "staged artifact");
        Path backup = plan.backupPath().toAbsolutePath().normalize();
        Path marker = plan.healthMarker().toAbsolutePath().normalize();
        ensureDistinct(install, staged, backup, marker);
        ensureSafeParent(backup, "backup");
        ensureSafeParent(marker, "health marker");
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) throw new SecurityException("Backup already exists");
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularNoLinks(marker, "stale health marker");
            Files.delete(marker);
        }

        boolean oldDetached = false;
        boolean newPublished = false;
        Process launched = null;
        try {
            moveNoReplace(install, backup, "detach existing installation");
            oldDetached = true;
            moveNoReplace(staged, install, "publish staged installation");
            newPublished = true;

            ProcessBuilder pb = new ProcessBuilder(options.launchCommand());
            pb.environment().put(HEALTH_ENV, marker.toString());
            launched = pb.start();
            long deadline = System.nanoTime() + options.healthTimeout().toNanos();
            while (System.nanoTime() < deadline) {
                if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                    requireRegularNoLinks(marker, "health marker");
                    Files.delete(marker);
                    Files.delete(backup);
                    Files.deleteIfExists(options.plan());
                    return new Result(true, false, launched.pid());
                }
                if (!launched.isAlive()) break;
                Thread.sleep(25L);
            }
            throw new IllegalStateException("Updated application did not report healthy startup");
        } catch (Exception failure) {
            if (launched != null && launched.isAlive()) launched.destroyForcibly();
            if (newPublished) {
                boolean verifiedOwnedReplacement = false;
                try {
                    verifyArtifact(install, plan.artifactLength(), plan.sha256());
                    verifiedOwnedReplacement = true;
                    Path failed = backup.getParent().resolve(backup.getFileName() + ".failed-new");
                    moveNoReplace(install, failed, "preserve failed update");
                    newPublished = false;
                } catch (Exception preserveFailure) {
                    failure.addSuppressed(preserveFailure);
                    // Preserving the failed candidate is diagnostic, not more important than
                    // restoring the known-good installation. Only remove it when its signed
                    // length/hash prove that it is exactly the replacement published above.
                    if (verifiedOwnedReplacement && Files.exists(install, LinkOption.NOFOLLOW_LINKS)) {
                        try {
                            verifyArtifact(install, plan.artifactLength(), plan.sha256());
                            Files.delete(requireRegularNoLinks(install, "failed update rollback cleanup"));
                            newPublished = false;
                        } catch (Exception cleanupFailure) {
                            failure.addSuppressed(cleanupFailure);
                        }
                    }
                }
            }
            if (oldDetached && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(install, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    moveNoReplace(backup, install, "rollback previous installation");
                } catch (Exception rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    private static void verifyManifest(Plan plan, Options options, Map<String, PublicKey> trust) throws Exception {
        if (!PRODUCT.equals(plan.productId())) throw new SecurityException("Wrong update product");
        if (!normalize(plan.platform()).equals(normalize(options.platform()))) throw new SecurityException("Wrong update platform");
        if (!normalize(plan.arch()).equals(normalize(options.arch()))) throw new SecurityException("Wrong update architecture");
        if (!"https".equalsIgnoreCase(URI.create(plan.artifactUrl()).getScheme())) throw new SecurityException("Artifact URL must use HTTPS");
        if (compareVersions(options.updaterVersion(), plan.minimumUpdaterVersion()) < 0) throw new SecurityException("Updater is too old");
        if (compareVersions(plan.version(), options.currentVersion()) < 0) throw new SecurityException("Downgrade is blocked");
        PublicKey key = trust.get(plan.keyId());
        if (key == null) throw new SecurityException("Unknown signing key");
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(plan.canonicalPayload());
        if (!verifier.verify(Base64.getDecoder().decode(plan.signature()))) throw new SecurityException("Invalid update signature");
    }

    private static void verifyArtifact(Path file, long length, String expectedSha256) throws Exception {
        Path safe = requireRegularNoLinks(file, "update artifact");
        if (Files.size(safe) != length) throw new SecurityException("Update artifact length mismatch");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(safe)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            for (int read; (read = in.read(buffer)) >= 0;) {
                if (read == 0) continue;
                total += read;
                if (total > length) throw new SecurityException("Update artifact exceeds manifest length");
                digest.update(buffer, 0, read);
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), expectedSha256.getBytes(StandardCharsets.US_ASCII))) {
            throw new SecurityException("Update artifact SHA-256 mismatch");
        }
    }

    private static void waitForParent(long pid, Duration timeout) throws InterruptedException {
        Optional<ProcessHandle> parent = ProcessHandle.of(pid);
        if (parent.isEmpty()) return;
        long deadline = System.nanoTime() + timeout.toNanos();
        while (parent.orElseThrow().isAlive() && System.nanoTime() < deadline) Thread.sleep(50L);
        if (parent.orElseThrow().isAlive()) throw new IllegalStateException("Main application did not exit before update timeout");
    }

    private static Map<String, PublicKey> loadKeys() throws Exception {
        try (InputStream in = ApplicationUpdaterMain.class.getResourceAsStream("/update/trusted-keys.properties")) {
            if (in == null) throw new SecurityException("Updater trust store missing");
            Properties p = new Properties(); p.load(in);
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            Map<String, PublicKey> keys = new HashMap<>();
            for (String id : p.stringPropertyNames()) {
                String encoded = p.getProperty(id, "").trim();
                if (!encoded.isBlank()) keys.put(id, factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded))));
            }
            if (keys.isEmpty()) throw new SecurityException("Updater trust store empty");
            return Map.copyOf(keys);
        }
    }

    private static Path requireRegularNoLinks(Path path, String role) throws Exception {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current == null ? part : current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(role + ": " + absolute);
            if (Files.isSymbolicLink(current)) throw new SecurityException(role + " contains symbolic link: " + current);
            BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isOther()) throw new SecurityException(role + " contains special entry: " + current);
        }
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) throw new SecurityException(role + " is not a regular file");
        return absolute;
    }

    private static void ensureSafeParent(Path target, String role) throws Exception {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new SecurityException(role + " has no parent");
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(parent);
        Path current = parent.getRoot();
        for (Path part : parent) {
            current = current == null ? part : current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new SecurityException(role + " parent contains symbolic link");
            BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isDirectory() || attrs.isOther()) throw new SecurityException(role + " parent is unsafe");
        }
    }

    private static void moveNoReplace(Path source, Path target, String role) throws Exception {
        requireRegularNoLinks(source, role + " source");
        ensureSafeParent(target, role + " target");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.toString());
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(source, target); }
    }

    private static void ensureDistinct(Path... paths) {
        Set<Path> normalized = new HashSet<>();
        for (Path p : paths) if (!normalized.add(p.toAbsolutePath().normalize())) throw new IllegalArgumentException("Update paths must be distinct");
    }

    static int compareVersions(String a, String b) {
        ParsedVersion x = ParsedVersion.parse(a), y = ParsedVersion.parse(b);
        int max = Math.max(x.numbers().length, y.numbers().length);
        for (int i = 0; i < max; i++) {
            long xv = i < x.numbers().length ? x.numbers()[i] : 0;
            long yv = i < y.numbers().length ? y.numbers()[i] : 0;
            if (xv != yv) return Long.compare(xv, yv);
        }
        if (x.pre().isEmpty() && !y.pre().isEmpty()) return 1;
        if (!x.pre().isEmpty() && y.pre().isEmpty()) return -1;
        return x.pre().compareTo(y.pre());
    }

    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-'); }

    record ParsedVersion(long[] numbers, String pre) {
        static ParsedVersion parse(String value) {
            String v = value == null ? "0" : value.trim();
            int plus = v.indexOf('+'); if (plus >= 0) v = v.substring(0, plus);
            String pre = ""; int dash = v.indexOf('-'); if (dash >= 0) { pre = v.substring(dash + 1); v = v.substring(0, dash); }
            String[] parts = v.split("\\."); long[] nums = new long[parts.length];
            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].matches("\\d+")) throw new IllegalArgumentException("Invalid version: " + value);
                nums[i] = Long.parseLong(parts[i]);
            }
            return new ParsedVersion(nums, pre);
        }
    }

    record Plan(String productId, String version, String channel, String platform, String arch, String artifactUrl,
                long artifactLength, String sha256, String signature, String keyId, String minimumUpdaterVersion,
                Path stagedArtifact, Path installationPath, Path backupPath, Path healthMarker) {
        static Plan load(Path file) throws Exception {
            requireRegularNoLinks(file, "update plan");
            Properties p = new Properties();
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); }
            return new Plan(req(p,"productId"), req(p,"version"), req(p,"channel"), req(p,"platform"), req(p,"arch"),
                    req(p,"artifactUrl"), Long.parseLong(req(p,"artifactLength")), req(p,"sha256").toLowerCase(Locale.ROOT),
                    req(p,"signature"), req(p,"keyId"), req(p,"minimumUpdaterVersion"), Path.of(req(p,"stagedArtifact")),
                    Path.of(req(p,"installationPath")), Path.of(req(p,"backupPath")), Path.of(req(p,"healthMarker")));
        }
        byte[] canonicalPayload() {
            return String.join("\n", productId, version, channel, platform, arch, artifactUrl,
                    Long.toString(artifactLength), sha256, keyId, minimumUpdaterVersion).getBytes(StandardCharsets.UTF_8);
        }
        private static String req(Properties p, String key) {
            String value = p.getProperty(key, "").trim();
            if (value.isBlank()) throw new SecurityException("Missing update plan field: " + key);
            return value;
        }
    }

    record Options(Path plan, long parentPid, String currentVersion, String updaterVersion, String platform, String arch,
                   List<String> launchCommand, Duration healthTimeout) {
        static Options parse(String[] args) {
            Map<String, String> one = new LinkedHashMap<>(); List<String> launch = new ArrayList<>();
            boolean launchMode = false;
            for (int i = 0; i < args.length; i++) {
                if (launchMode) { launch.add(args[i]); continue; }
                if ("--launch".equals(args[i])) { launchMode = true; continue; }
                if (!args[i].startsWith("--") || i + 1 >= args.length) throw new IllegalArgumentException("Invalid updater arguments");
                one.put(args[i], args[++i]);
            }
            if (launch.isEmpty()) throw new IllegalArgumentException("--launch command is required");
            return new Options(Path.of(required(one,"--plan")), Long.parseLong(one.getOrDefault("--parent-pid","0")),
                    required(one,"--current-version"), required(one,"--updater-version"), required(one,"--platform"), required(one,"--arch"),
                    List.copyOf(launch), Duration.ofSeconds(Long.parseLong(one.getOrDefault("--health-timeout-seconds","30"))));
        }
        private static String required(Map<String,String> map, String key) {
            String v = map.get(key); if (v == null || v.isBlank()) throw new IllegalArgumentException(key + " is required"); return v;
        }
    }

    record Result(boolean installed, boolean rolledBack, long pid) { }
}
