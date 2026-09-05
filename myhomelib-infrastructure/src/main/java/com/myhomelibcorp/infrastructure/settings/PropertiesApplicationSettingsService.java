package com.myhomelibcorp.infrastructure.settings;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.AtomicFileSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

@Component
@Slf4j
public class PropertiesApplicationSettingsService implements ApplicationSettingsPort {
    private final Path file = AppPaths.configDir().resolve("myhomelib.properties");
    private final Properties properties = new Properties();

    public PropertiesApplicationSettingsService() {
        load();
    }

    private synchronized void load() {
        try {
            Files.createDirectories(file.getParent());
            if (Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) { properties.load(in); }
            }
        } catch (Exception e) {
            log.warn("Cannot load application settings from {}", file, e);
        }
    }

    private synchronized void flush() {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(tmp)) {
                properties.store(out, "MyHomeLib 7.1.0 settings");
            }
            AtomicFileSupport.moveReplacing(tmp, file);
        } catch (Exception e) {
            try { Files.deleteIfExists(tmp); }
            catch (Exception cleanup) { e.addSuppressed(cleanup); }
            log.error("Cannot save application settings to {}", file, e);
            throw new IllegalStateException("Cannot save application settings: " + file, e);
        }
    }

    @Override public synchronized String get(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    @Override public synchronized void put(String key, String value) {
        if (value == null) properties.remove(key); else properties.setProperty(key, value);
        flush();
    }

    @Override public synchronized void remove(String key) {
        properties.remove(key);
        flush();
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
