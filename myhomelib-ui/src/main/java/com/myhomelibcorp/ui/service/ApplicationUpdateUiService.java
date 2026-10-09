package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.settings.ApplicationSettingsQueryService;
import com.myhomelibcorp.application.update.*;
import com.myhomelibcorp.shared.util.AppPaths;
import javafx.application.Platform;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** User-facing bridge for secure application updates. */
@Component
public final class ApplicationUpdateUiService {
    private final CheckForApplicationUpdateUseCase check;
    private final StageApplicationUpdateUseCase stage;
    private final GetPendingApplicationUpdateUseCase pending;
    private final ApplicationSettingsQueryService settings;

    public ApplicationUpdateUiService(CheckForApplicationUpdateUseCase check,
                                      StageApplicationUpdateUseCase stage,
                                      GetPendingApplicationUpdateUseCase pending,
                                      ApplicationSettingsQueryService settings) {
        this.check = check;
        this.stage = stage;
        this.pending = pending;
        this.settings = settings;
    }

    public Optional<ReleaseManifest> checkNow(UpdateChannel channel) throws Exception {
        String version = SupportBundleService.runtimeVersion();
        if ("development".equalsIgnoreCase(version)) version = System.getProperty("myhomelib.update.currentVersion", "8.0.0");
        return check.execute(new CheckForApplicationUpdateUseCase.Request(channel, "myhomelib", version, version,
                platform(), arch(), false));
    }

    public UpdatePlan stage(ReleaseManifest manifest) throws Exception {
        Path installation = resolveInstallationPath().orElseThrow(() ->
                new IllegalStateException("Application update installation path is not configured for this package"));
        return stage.execute(manifest, installation, AppPaths.dataDir().resolve("updates"), AppPaths.portableMode());
    }

    public Optional<UpdatePlan> pendingPlan() throws Exception { return pending.execute(); }

    public boolean canRestartAndApply() {
        return resolveInstallationPath().isPresent() && resolveUpdaterJar().isPresent() && currentLauncher().isPresent();
    }

    public void restartAndApply() throws Exception {
        UpdatePlan plan = pending.execute().orElseThrow(() -> new IllegalStateException("No staged application update"));
        Path updater = resolveUpdaterJar().orElseThrow(() -> new IllegalStateException("Updater helper JAR is not available"));
        Path launcher = currentLauncher().orElseThrow(() -> new IllegalStateException("Application launcher path is unavailable"));
        String current = SupportBundleService.runtimeVersion();
        if ("development".equalsIgnoreCase(current)) current = System.getProperty("myhomelib.update.currentVersion", "8.0.0");
        String javaExe = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        List<String> command = new ArrayList<>();
        command.add(javaExe);
        command.add("-jar"); command.add(updater.toString());
        command.add("--plan"); command.add(AppPaths.dataDir().resolve("updates/pending-update.properties").toString());
        command.add("--parent-pid"); command.add(Long.toString(ProcessHandle.current().pid()));
        command.add("--current-version"); command.add(current);
        command.add("--updater-version"); command.add(current);
        command.add("--platform"); command.add(platform());
        command.add("--arch"); command.add(arch());
        command.add("--health-timeout-seconds"); command.add("60");
        command.add("--launch"); command.add(launcher.toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectError(AppPaths.logsDir().resolve("application-updater.err.log").toFile());
        builder.redirectOutput(AppPaths.logsDir().resolve("application-updater.out.log").toFile());
        builder.start();
        Platform.exit();
    }

    public Optional<Path> resolveInstallationPath() {
        String explicit = firstNonBlank(settings.get("update.installationPath", ""), System.getProperty("myhomelib.update.installationPath", ""));
        if (!explicit.isBlank()) return regular(Path.of(explicit));
        if (System.getProperty("jpackage.app-version", "").isBlank()) return Optional.empty();
        String version = System.getProperty("jpackage.app-version");
        Path launch = AppPaths.launchDir();
        List<Path> candidates = List.of(
                launch.resolve("../lib/app/myhomelib-bootstrap-" + version + ".jar"),
                launch.resolve("app/myhomelib-bootstrap-" + version + ".jar"),
                launch.resolve("../app/myhomelib-bootstrap-" + version + ".jar"),
                launch.resolve("../Resources/app/myhomelib-bootstrap-" + version + ".jar"));
        return candidates.stream().map(Path::normalize).filter(Files::isRegularFile).findFirst();
    }

    public Optional<Path> resolveUpdaterJar() {
        String explicit = firstNonBlank(settings.get("update.helperJar", ""), System.getProperty("myhomelib.update.helperJar", ""));
        if (!explicit.isBlank()) return regular(Path.of(explicit));
        String version = System.getProperty("jpackage.app-version", SupportBundleService.runtimeVersion());
        Path launch = AppPaths.launchDir();
        List<Path> candidates = List.of(
                launch.resolve("../lib/app/tools/updater/myhomelib-updater-" + version + ".jar"),
                launch.resolve("tools/updater/myhomelib-updater-" + version + ".jar"),
                launch.resolve("../tools/updater/myhomelib-updater-" + version + ".jar"));
        return candidates.stream().map(Path::normalize).filter(Files::isRegularFile).findFirst();
    }

    private static Optional<Path> currentLauncher() {
        return ProcessHandle.current().info().command().filter(v -> !v.isBlank()).map(Path::of).map(Path::toAbsolutePath).map(Path::normalize);
    }
    private static Optional<Path> regular(Path path) {
        try { Path p = path.toAbsolutePath().normalize(); return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty(); }
        catch (RuntimeException invalid) { return Optional.empty(); }
    }
    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        return "linux";
    }
    public static String arch() {
        String a = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (a.equals("amd64") || a.equals("x86_64")) return "x64";
        if (a.equals("aarch64") || a.equals("arm64")) return "arm64";
        return a.replace('_','-');
    }
    private static boolean isWindows() { return platform().equals("windows"); }
    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        return b == null ? "" : b.trim();
    }
}
