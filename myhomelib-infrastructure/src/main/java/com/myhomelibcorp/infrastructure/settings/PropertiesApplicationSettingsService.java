package com.myhomelibcorp.infrastructure.settings;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.BoundedIoSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

@Component
@Slf4j
public class PropertiesApplicationSettingsService implements ApplicationSettingsPort {
    private static final int MAX_SETTINGS_BYTES = 4 * 1024 * 1024;
    private final Path file = AppPaths.configDir().resolve("myhomelib.properties");
    private final Properties properties = new Properties();
    private Exception loadFailure;

    public PropertiesApplicationSettingsService() {
        load();
    }

    private synchronized void load() {
        Properties loaded = new Properties();
        try {
            SafeFilePublication.createDirectoriesNoLinks(file.getParent(), "application settings directory");
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                Path safe = SafeFilePublication.requireSafeRegularFile(file, "application settings");
                try (InputStream in = Files.newInputStream(safe, LinkOption.NOFOLLOW_LINKS);
                     InputStream bounded = new java.io.ByteArrayInputStream(
                             BoundedIoSupport.readFully(in, MAX_SETTINGS_BYTES))) {
                    loaded.load(bounded);
                }
            }
            properties.clear();
            properties.putAll(loaded);
            loadFailure = null;
        } catch (Exception e) {
            // Do not retain a partially parsed Properties object. More importantly, remember the
            // failure so a later write cannot replace the unreadable source with defaults.
            properties.clear();
            loadFailure = e;
            log.warn("Cannot load application settings from {}", file, e);
        }
    }

    private synchronized void flush() {
        if (loadFailure != null) {
            throw new IllegalStateException(
                    "Cannot save application settings because the existing settings file could not be loaded: " + file,
                    loadFailure);
        }
        Path workspace = null;
        try {
            String expectedTargetHash = SafeFilePublication.snapshotExistingTarget(file, "application settings");
            workspace = SafeFilePublication.createPrivateWorkspace(
                    file.getParent(), ".settings-work-", "application settings workspace");
            Path tmp = workspace.resolve("myhomelib.properties");
            try (OutputStream out = SafeFilePublication.openFreshOutputStream(tmp, "staged application settings")) {
                properties.store(out, "MyHomeLib 8.0.0 settings");
            }
            SafeFilePublication.publishReplacingIfUnchanged(
                    tmp, file, expectedTargetHash, "application settings publication");
        } catch (Exception e) {
            log.error("Cannot save application settings to {}", file, e);
            throw new IllegalStateException("Cannot save application settings: " + file, e);
        } finally {
            if (workspace != null) {
                try { SafeFilePublication.cleanupPrivateWorkspace(workspace); }
                catch (Exception ignored) { }
            }
        }
    }

    @Override public synchronized String get(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    @Override public synchronized void put(String key, String value) {
        String before = properties.getProperty(key);
        if (value == null) properties.remove(key); else properties.setProperty(key, value);
        try {
            flush();
        } catch (RuntimeException failure) {
            if (before == null) properties.remove(key); else properties.setProperty(key, before);
            throw failure;
        }
    }

    @Override public synchronized void remove(String key) {
        String before = properties.getProperty(key);
        properties.remove(key);
        try {
            flush();
        } catch (RuntimeException failure) {
            if (before != null) properties.setProperty(key, before);
            throw failure;
        }
    }

    @Override public synchronized Map<String, String> findByPrefix(String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        properties.stringPropertyNames().stream().sorted().filter(k -> k.startsWith(prefix))
                .forEach(k -> result.put(k, properties.getProperty(k)));
        return result;
    }

    @Override
    public synchronized void replaceByPrefix(String prefix, Map<String, String> values) {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(values, "values");
        for (String key : values.keySet()) {
            if (key == null || !key.startsWith(prefix)) {
                throw new IllegalArgumentException("Setting key is outside prefix " + prefix + ": " + key);
            }
        }

        Map<String, String> before = findByPrefix(prefix);
        properties.stringPropertyNames().stream().filter(k -> k.startsWith(prefix)).toList()
                .forEach(properties::remove);
        values.forEach((key, value) -> {
            if (value != null) properties.setProperty(key, value);
        });
        try {
            flush();
        } catch (RuntimeException failure) {
            properties.stringPropertyNames().stream().filter(k -> k.startsWith(prefix)).toList()
                    .forEach(properties::remove);
            before.forEach(properties::setProperty);
            throw failure;
        }
    }
}
