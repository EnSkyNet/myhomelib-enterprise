package com.myhomelibcorp.testisolation;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.Preferences;

/** Opt-in harness, built with the JDK outside Maven target directories. */
public final class IsolationAgent {
    public static void premain(String argument, Instrumentation instrumentation) throws Exception {
        Path root = Path.of(argument).toAbsolutePath().normalize();
        if (!root.isAbsolute() || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("An existing isolated run root is required");
        }
        for (Path current = root; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current) || Files.readAttributes(current,
                    java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther()) {
                throw new IllegalStateException("Linked run root: " + current);
            }
        }
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith("mhl.real.")) throw new IllegalStateException("Real-data probe forbidden: " + key);
        }
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (type.getName().startsWith("java.util.prefs.WindowsPreferences")) {
                throw new IllegalStateException("Native Preferences loaded before isolation");
            }
        }
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                    ProtectionDomain domain, byte[] bytes) {
                if (name != null && name.startsWith("java/util/prefs/WindowsPreferences")) {
                    System.err.println("ISOLATION_FAILURE: native Windows Preferences requested");
                    Runtime.getRuntime().halt(93);
                }
                return null;
            }
        });
        System.setProperty("java.util.prefs.PreferencesFactory", MemoryPreferencesFactory.class.getName());
        if (!Preferences.userRoot().getClass().getName().startsWith(MemoryPreferencesFactory.class.getName())
                || !Preferences.systemRoot().getClass().getName().startsWith(MemoryPreferencesFactory.class.getName())) {
            throw new IllegalStateException("In-memory Preferences factory not active");
        }

        Path process = root.resolve("jvm-" + ProcessHandle.current().pid());
        Files.createDirectories(process);
        Map<String, Path> paths = new LinkedHashMap<>();
        paths.put("user.home", process.resolve("home"));
        paths.put("java.io.tmpdir", process.resolve("tmp"));
        paths.put("myhomelib.dataDir", process.resolve("home/.myhomelibcorp"));
        paths.put("myhomelib.launchDir", process.resolve("launch"));
        for (var entry : paths.entrySet()) {
            // A crash-worker may explicitly share a parent's isolated data directory.
            String existing = System.getProperty(entry.getKey());
            Path path = existing == null ? entry.getValue() : Path.of(existing).toAbsolutePath().normalize();
            if (!path.startsWith(root)) path = entry.getValue();
            Files.createDirectories(path);
            System.setProperty(entry.getKey(), path.toString());
        }
        Path data = Path.of(System.getProperty("myhomelib.dataDir"));
        setDefaultInside(root, "app.metadata.db-path", data.resolve("meta.db"));
        setDefaultInside(root, "myhomelib.logDir", data.resolve("logs"));
        setDefaultInside(root, "logging.file.name", data.resolve("logs/myhomelib.log"));
        setDefaultInside(root, "app.search.index-path", data.resolve("search-index"));
        System.setProperty("mhl.isolation.root", root.toString());
        StringBuilder audit = new StringBuilder("preferences=memory\n");
        for (String key : new String[]{"user.home", "java.io.tmpdir", "myhomelib.dataDir", "myhomelib.launchDir",
                "app.metadata.db-path", "myhomelib.logDir", "logging.file.name", "app.search.index-path"}) {
            audit.append(key).append('=').append(System.getProperty(key)).append('\n');
        }
        Files.writeString(process.resolve("isolation.txt"), audit);
    }

    private static void setDefaultInside(Path root, String key, Path fallback) {
        String configured = System.getProperty(key);
        if (configured == null || !Path.of(configured).toAbsolutePath().normalize().startsWith(root)) {
            System.setProperty(key, fallback.toString());
        }
    }
}
