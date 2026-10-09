package com.myhomelibcorp.update;

import com.myhomelibcorp.application.update.ApplicationUpdateTrustPort;
import com.myhomelibcorp.application.update.UpdatePlan;
import com.myhomelibcorp.application.update.UpdateVerificationService;
import com.myhomelibcorp.shared.util.AtomicFileSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Out-of-process installer primitive used by the updater helper.
 *
 * <p>The running application only stages a signed candidate. This class is intended to execute
 * in a separate JVM/process after the main application has stopped. It re-verifies the signed
 * manifest and artifact, preserves the old installation, launches the replacement and requires
 * an explicit health marker. Missing health evidence triggers rollback.</p>
 */
public final class ApplicationUpdateInstaller {
    public static final String HEALTH_MARKER_ENV = "MYHOMELIB_UPDATE_HEALTH_MARKER";

    private final UpdateVerificationService verification;

    public ApplicationUpdateInstaller(ApplicationUpdateTrustPort trust) {
        this.verification = new UpdateVerificationService(Objects.requireNonNull(trust, "trust"));
    }

    public Result apply(UpdatePlan plan, VerificationContext context, List<String> launchCommand, Duration healthTimeout) throws Exception {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(context, "context");
        if (launchCommand == null || launchCommand.isEmpty()) throw new IllegalArgumentException("launchCommand is required");
        Duration timeout = healthTimeout == null ? Duration.ofSeconds(30) : healthTimeout;
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("healthTimeout must be within (0, 5 minutes]");
        }

        verification.verifyManifest(plan.manifest(), context.productId(), context.currentVersion(), context.updaterVersion(),
                context.platform(), context.arch(), false);
        verification.verifyArtifact(plan.stagedArtifact(), plan.manifest());

        Path install = AtomicFileSupport.requireSafeRegularFile(plan.installationPath(), "application update installation");
        Path staged = AtomicFileSupport.requireSafeRegularFile(plan.stagedArtifact(), "application update staged artifact");
        Path backup = plan.backupPath().toAbsolutePath().normalize();
        Path marker = plan.healthMarker().toAbsolutePath().normalize();
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Update backup already exists: " + backup);
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            AtomicFileSupport.requireSafeRegularFile(marker, "stale update health marker");
            Files.delete(marker);
        }

        Path backupParent = backup.getParent();
        if (backupParent == null) throw new IOException("Update backup path has no parent");
        AtomicFileSupport.createDirectoriesNoLinks(backupParent, "application update backup directory");
        Files.copy(install, backup, StandardCopyOption.COPY_ATTRIBUTES);
        AtomicFileSupport.requireSafeRegularFile(backup, "application update backup");

        boolean replacementPublished = false;
        Process launched = null;
        try {
            // AtomicFileSupport snapshots/rechecks the old target and publishes only a verified regular file.
            AtomicFileSupport.moveReplacing(staged, install);
            replacementPublished = true;

            ProcessBuilder builder = new ProcessBuilder(List.copyOf(launchCommand));
            builder.environment().put(HEALTH_MARKER_ENV, marker.toString());
            launched = builder.start();

            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                    AtomicFileSupport.requireSafeRegularFile(marker, "application update health marker");
                    Files.deleteIfExists(backup);
                    return new Result(true, false, launched.pid(), "healthy");
                }
                if (!launched.isAlive()) break;
                Thread.sleep(25L);
            }
            throw new IOException("Updated application did not report healthy startup");
        } catch (Exception failure) {
            if (launched != null && launched.isAlive()) launched.destroyForcibly();
            if (replacementPublished) rollbackIfOwned(plan, install, backup);
            throw failure;
        }
    }

    private void rollbackIfOwned(UpdatePlan plan, Path install, Path backup) throws Exception {
        // Never overwrite a target that no longer contains the staged artifact. Preserve evidence instead.
        try {
            verification.verifyArtifact(install, plan.manifest());
        } catch (Exception changed) {
            throw new IOException("Installed target changed after update; rollback backup preserved at " + backup, changed);
        }
        AtomicFileSupport.requireSafeRegularFile(backup, "application update rollback backup");
        AtomicFileSupport.moveReplacing(backup, install);
    }

    public record VerificationContext(String productId, String currentVersion, String updaterVersion, String platform, String arch) {
        public VerificationContext {
            productId = required(productId, "productId");
            currentVersion = required(currentVersion, "currentVersion");
            updaterVersion = required(updaterVersion, "updaterVersion");
            platform = required(platform, "platform");
            arch = required(arch, "arch");
        }
        private static String required(String value, String name) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
            return value.trim();
        }
    }

    public record Result(boolean installed, boolean rolledBack, long launchedPid, String status) { }
}
