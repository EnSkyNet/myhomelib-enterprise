package com.myhomelibcorp.shared.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessWorkspaceCleanupTest {
    @Test
    void refusesWorkspaceReplacedByFile(@TempDir Path directory) throws Exception {
        Path workspace = Files.writeString(directory.resolve("workspace"), "foreign file");
        assertThatThrownBy(() -> NoLinkPathSafety.cleanupPrivateWorkspaceAfterProcess(workspace, "test"))
                .isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(workspace)).isEqualTo("foreign file");
    }

    @Test
    void refusesLinkedWorkspaceWithoutTouchingOutsideFiles(@TempDir Path directory) throws Exception {
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path book = Files.writeString(outside.resolve("book.fb2"), "preserved book");
        Path workspace = directory.resolve("workspace");
        try {
            Files.createSymbolicLink(workspace, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            org.junit.jupiter.api.Assumptions.abort("Symlinks unavailable: " + unsupported.getClass().getSimpleName());
        }
        assertThatThrownBy(() -> NoLinkPathSafety.cleanupPrivateWorkspaceAfterProcess(workspace, "test"))
                .isInstanceOf(java.io.IOException.class);
        assertThat(Files.isSymbolicLink(workspace)).isTrue();
        assertThat(Files.readString(book)).isEqualTo("preserved book");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void permanentSharingViolationIsBoundedAndPreservesInterruptedStatus(@TempDir Path directory) throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
        Path file = Files.writeString(workspace.resolve("input.fb2"), "retained input");
        try (FileChannel ignored = FileChannel.open(file, StandardOpenOption.READ,
                com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) {
            long started = System.nanoTime();
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> NoLinkPathSafety.cleanupPrivateWorkspaceAfterProcess(workspace, "test"))
                        .isInstanceOf(java.nio.file.FileSystemException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(5));
            assertThat(Files.readString(file)).isEqualTo("retained input");
        }
        NoLinkPathSafety.cleanupPrivateWorkspaceAfterProcess(workspace, "test");
        assertThat(workspace).doesNotExist();
    }

    private static List<String> javaCommand(Class<?> mainClass) {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java").toString();
        return List.of(java, "-cp", System.getProperty("java.class.path"), mainClass.getName());
    }

    @Test
    void cancellationTerminatesTreeAndReleasesFileForCleanup(@TempDir Path directory) throws Exception {
        assertTreeTerminationReleasesFileForCleanup(directory, false);
    }

    @Test
    void interruptionTerminatesTreeAndReleasesFileForCleanup(@TempDir Path directory) throws Exception {
        assertTreeTerminationReleasesFileForCleanup(directory, true);
    }

    private static void assertTreeTerminationReleasesFileForCleanup(Path directory, boolean interrupt) throws Exception {
        Path ready = directory.resolve("ready.txt");
        Path locked = directory.resolve("converter-input.fb2");
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<Throwable> outcome = new CompletableFuture<>();
        var command = new java.util.ArrayList<>(javaCommand(SpawnLockingChild.class));
        command.add(ready.toString());
        command.add(locked.toString());
        Thread worker = Thread.startVirtualThread(() -> {
            try {
                ProcessExecutionSupport.run(new ProcessBuilder(command), null, Duration.ofSeconds(20), 4096,
                        cancelled::get);
                outcome.complete(null);
            } catch (Throwable failure) {
                outcome.complete(failure);
            }
        });
        List<ProcessHandle> handles = new java.util.ArrayList<>();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            String pids = "";
            while (System.nanoTime() < deadline && !outcome.isDone()) {
                if (Files.exists(ready)) pids = Files.readString(ready).trim();
                if (pids.matches("[0-9]+ [0-9]+")) break;
                Thread.sleep(25);
            }
            assertThat(pids).as("Parent and locked child must be ready before cancellation")
                    .matches("[0-9]+ [0-9]+");
            for (String pid : pids.split(" ")) handles.add(ProcessHandle.of(Long.parseLong(pid)).orElseThrow());
            assertThat(handles).allMatch(ProcessHandle::isAlive);
            if (System.getProperty("os.name", "").startsWith("Windows")) {
                assertThatThrownBy(() -> Files.delete(locked)).isInstanceOf(java.io.IOException.class);
            }

            if (interrupt) worker.interrupt();
            else cancelled.set(true);

            assertThat(outcome.get(15, TimeUnit.SECONDS))
                    .isInstanceOf(interrupt ? InterruptedException.class : CancellationException.class);
            assertThat(handles).noneMatch(ProcessHandle::isAlive);
            NoLinkPathSafety.cleanupPrivateWorkspaceAfterProcess(directory, "Converter test cleanup");
            assertThat(directory).doesNotExist();
        } finally {
            cancelled.set(true);
            worker.interrupt();
            for (ProcessHandle handle : handles) if (handle.isAlive()) handle.destroyForcibly();
            worker.join(15_000);
        }
    }


    public static class SpawnLockingChild {
        public static void main(String[] args) throws Exception {
            var command = new java.util.ArrayList<>(javaCommand(LockingChild.class));
            command.addAll(List.of(args));
            new ProcessBuilder(command).inheritIO().start();
            Thread.sleep(30_000);
        }
    }

    public static class LockingChild {
        public static void main(String[] args) throws Exception {
            var options = new java.util.HashSet<java.nio.file.OpenOption>(
                    List.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE));
            if (System.getProperty("os.name", "").startsWith("Windows")) {
                options.add(com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE);
            }
            try (FileChannel channel = FileChannel.open(Path.of(args[1]), options);
                 var lock = channel.lock()) {
                Files.writeString(Path.of(args[0]), ProcessHandle.current().parent().orElseThrow().pid()
                        + " " + ProcessHandle.current().pid());
                Thread.sleep(30_000);
            }
        }
    }
}
