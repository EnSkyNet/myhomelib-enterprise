package com.myhomelibcorp.infrastructure.contentindexing;

import com.myhomelibcorp.application.port.out.contentindexing.PowerStatePort;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

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
public class SystemPowerStateAdapter implements PowerStatePort, DisposableBean {
    private static final long CACHE_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final AtomicBoolean refreshRunning = new AtomicBoolean();
    private final ExecutorService detector = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mhl-power-state-detector");
        thread.setDaemon(true);
        return thread;
    });
    private volatile long refreshAfter;
    private volatile boolean cached;

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
        try {
            if (os.contains("linux")) return linuxOnBattery();
            if (os.contains("mac")) return commandContains(new String[]{"pmset", "-g", "batt"}, "Battery Power");
            if (os.contains("win")) return commandContains(new String[]{"powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "$b=Get-CimInstance Win32_Battery -ErrorAction SilentlyContinue | Select-Object -First 1; if($b -and $b.BatteryStatus -eq 1){'ON_BATTERY'}"}, "ON_BATTERY");
        } catch (Exception ignored) { }
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

    private static String read(Path file) {
        try { return Files.readString(file, StandardCharsets.UTF_8).trim(); }
        catch (Exception ignored) { return ""; }
    }

    private static boolean commandContains(String[] command, String token) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            return false;
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return output.contains(token);
    }

    @Override public void destroy() { detector.shutdownNow(); }
}
