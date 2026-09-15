package com.myhomelibcorp.infrastructure.contentindexing;

import com.myhomelibcorp.application.port.out.contentindexing.PowerStatePort;
import com.myhomelibcorp.shared.util.ProcessExecutionSupport;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Non-blocking cached OS power-state detector. Slow host probes never run on the caller/UI thread. */
@Component
@Slf4j
public class SystemPowerStateAdapter implements PowerStatePort, DisposableBean {
    private static final long CACHE_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final AtomicBoolean refreshRunning = new AtomicBoolean();
    private final ExecutorService detector = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mhl-power-state-detector");
        thread.setDaemon(true);
        return thread;
    });
    private volatile long refreshAfter;
    private volatile boolean cached = conservativeInitialState();


    private static boolean conservativeInitialState() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("linux") || os.contains("mac") || os.contains("win");
    }

    @Override
    public boolean onBatteryPower() {
        long now = System.nanoTime();
        if (now >= refreshAfter && refreshRunning.compareAndSet(false, true)) {
            refreshAfter = now + CACHE_NANOS;
            detector.execute(() -> {
                try { cached = detect(); }
                finally { refreshRunning.set(false); }
            });
        }
        return cached;
    }

    private static boolean detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean knownPlatform = os.contains("linux") || os.contains("mac") || os.contains("win");
        try {
            if (os.contains("linux")) return linuxOnBattery();
            if (os.contains("mac")) return commandContains(new String[]{"pmset", "-g", "batt"}, "Battery Power");
            if (os.contains("win")) return commandContains(new String[]{"powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "$b=Get-CimInstance Win32_Battery -ErrorAction SilentlyContinue | Select-Object -First 1; if($b -and $b.BatteryStatus -eq 1){'ON_BATTERY'}"}, "ON_BATTERY");
        } catch (Exception e) {
            // Power state is a resource-safety signal. On supported platforms an unavailable probe
            // is treated conservatively as battery power so indexing does not start heavy work on
            // an unknown host state. The next cached refresh retries automatically.
            log.debug("Cannot determine OS power state; using conservative battery mode: {}", e.toString());
            return knownPlatform;
        }
        return false;
    }

    private static boolean linuxOnBattery() throws Exception {
        Path root = Path.of("/sys/class/power_supply");
        if (!Files.isDirectory(root)) return false;
        boolean batteryPresent = false;
        boolean externalOnline = false;
        try (var entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                String type = read(entry.resolve("type"));
                if ("Battery".equalsIgnoreCase(type)) batteryPresent = true;
                if (("Mains".equalsIgnoreCase(type) || "USB".equalsIgnoreCase(type) || "USB_C".equalsIgnoreCase(type))
                        && "1".equals(read(entry.resolve("online")))) externalOnline = true;
            }
        }
        return batteryPresent && !externalOnline;
    }

    private static String read(Path file) throws java.io.IOException {
        return Files.readString(file, StandardCharsets.UTF_8).trim();
    }

    private static boolean commandContains(String[] command, String token) throws Exception {
        ProcessExecutionSupport.Result result = ProcessExecutionSupport.run(
                java.util.List.of(command), null, java.time.Duration.ofMillis(1500), 64 * 1024);
        if (result.exitCode() != 0) {
            throw new java.io.IOException("Power-state probe exited with code " + result.exitCode());
        }
        return result.stdoutText().contains(token);
    }

    @Override public void destroy() { detector.shutdownNow(); }
}
