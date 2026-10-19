package com.myhomelibcorp.shared.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessExecutionSupportTest {

    @Test
    void cancellationBeforeStartDoesNotLaunchAnExecutable() {
        assertThatThrownBy(() -> ProcessExecutionSupport.run(
                new ProcessBuilder("nonexistent-cancelled-converter"), null, Duration.ofSeconds(5), 4096,
                () -> true))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void cancellationBeforeStartDoesNotTruncateRedirectedOutput(@TempDir Path directory) throws Exception {
        Path output = directory.resolve("existing-output.txt");
        Files.writeString(output, "existing content");

        assertThatThrownBy(() -> ProcessExecutionSupport.run(
                new ProcessBuilder(javaCommand(Sleeper.class)).redirectOutput(output.toFile()),
                null, Duration.ofSeconds(5), 4096, () -> true))
                .isInstanceOf(CancellationException.class);

        assertThat(Files.readString(output)).isEqualTo("existing content");
    }

    @Test
    void interruptionBeforeStartDoesNotTruncateRedirectedOutput(@TempDir Path directory) throws Exception {
        Path output = directory.resolve("existing-output.txt");
        Files.writeString(output, "existing content");

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> ProcessExecutionSupport.run(
                    new ProcessBuilder(javaCommand(Sleeper.class)).redirectOutput(output.toFile()),
                    null, Duration.ofSeconds(5), 4096))
                    .isInstanceOf(InterruptedException.class);
        } finally {
            Thread.interrupted();
        }

        assertThat(Files.readString(output)).isEqualTo("existing content");
    }

    @Test
    void drainsLargeStdoutAndStderrWithoutDeadlockAndBoundsCapturedOutput() throws Exception {
        var result = ProcessExecutionSupport.run(javaCommand(OutputFlood.class), null, Duration.ofSeconds(10), 32 * 1024);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).hasSize(32 * 1024);
        assertThat(result.stderr()).hasSize(32 * 1024);
        assertThat(result.stdoutTruncated()).isTrue();
        assertThat(result.stderrTruncated()).isTrue();
    }

    @Test
    void timesOutAndTerminatesProcess() {
        assertThatThrownBy(() -> ProcessExecutionSupport.run(
                javaCommand(Sleeper.class), null, Duration.ofMillis(150), 4096))
                .isInstanceOf(ProcessExecutionSupport.ProcessTimeoutException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void cancellationTerminatesProcessInsteadOfWaitingForTimeout() {
        AtomicInteger polls = new AtomicInteger();
        assertThatThrownBy(() -> ProcessExecutionSupport.run(
                new ProcessBuilder(javaCommand(Sleeper.class)), null, Duration.ofSeconds(30), 4096,
                () -> polls.incrementAndGet() >= 2))
                .isInstanceOf(CancellationException.class)
                .hasMessageContaining("cancelled");
        assertThat(polls.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void timeoutTerminatesDescendantProcessTree() throws Exception {
        Path pidFile = Files.createTempFile("mhl-child-pid-", ".txt");
        Files.deleteIfExists(pidFile);
        List<String> command = new java.util.ArrayList<>(javaCommand(SpawnChildAndSleep.class));
        command.add(pidFile.toString());

        // Starting a JVM that itself spawns another JVM can take longer than
        // 500 ms on a loaded CI host. Wait until the child is provably running
        // before checking that the timeout terminates its entire process tree.
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var execution = pool.submit(() -> ProcessExecutionSupport.run(
                    command, null, Duration.ofSeconds(6), 4096));
            long readyDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            // Files.writeString first creates the PID file, then writes its content.
            // Seeing the empty file is not proof that the child PID is ready.
            String pidText = "";
            while (!execution.isDone() && System.nanoTime() < readyDeadline) {
                if (Files.exists(pidFile)) pidText = Files.readString(pidFile).trim();
                if (pidText.matches("[0-9]+")) break;
                Thread.sleep(25);
            }
            assertThat(pidText).as("The spawned child must publish a complete numeric PID before timeout")
                    .matches("[0-9]+");
            long childPid = Long.parseLong(pidText);
            assertThatThrownBy(() -> execution.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ProcessExecutionSupport.ProcessTimeoutException.class);
            long terminatedDeadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)
                    && System.nanoTime() < terminatedDeadline) Thread.sleep(25);
            assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        } finally {
            Files.deleteIfExists(pidFile);
        }
    }

    @Test
    void terminateWaitsForDescendantsAfterForcibleTermination() {
        DelayedTerminationHandle child = new DelayedTerminationHandle(2002, List.of(), true);
        DelayedTerminationHandle root = new DelayedTerminationHandle(2001, List.of(child), false);
        DelayedTerminationProcess process = new DelayedTerminationProcess(root);

        ProcessExecutionSupport.terminate(process);

        assertThat(root.isAlive()).isFalse();
        assertThat(child.isAlive()).as("terminate must not return while a forcibly-killed child is still alive").isFalse();
    }

    @Test
    void terminationGraceWaitsBeyondLegacyTwoSecondsForSlowNativeDescendant() throws Exception {
        DelayedTerminationHandle child = new DelayedTerminationHandle(2012, List.of(), 2500);
        DelayedTerminationHandle root = new DelayedTerminationHandle(2011, List.of(), 0);
        DelayedTerminationProcess process = new DelayedTerminationProcess(root);
        root.destroyForcibly();
        child.destroyForcibly();

        var await = ProcessExecutionSupport.class.getDeclaredMethod(
                "awaitTermination", Process.class, List.class);
        await.setAccessible(true);
        await.invoke(null, process, List.of(child));

        assertThat(child.isAlive())
                .as("cleanup must not run while a slowly terminating native child can still hold input.fb2")
                .isFalse();
    }

    @Test
    void writesStdinAndCapturesResponse() throws Exception {
        var result = ProcessExecutionSupport.run(javaCommand(EchoStdin.class), "secret-value", Duration.ofSeconds(5), 4096);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdoutText()).isEqualTo("len=12");
        // JVM-injected agents/options are inherited deliberately by subprocesses.
        // Require precisely the launcher's diagnostics, retaining all other stderr checks.
        var expectedDiagnostics = new java.util.ArrayList<String>();
        for (String variable : List.of("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS")) {
            String value = System.getenv(variable);
            if (value != null) expectedDiagnostics.add((variable.equals("JDK_JAVA_OPTIONS") ? "NOTE: " : "")
                    + "Picked up " + variable + ": " + value);
        }
        assertThat(result.stderrText().lines().toList()).containsExactlyElementsOf(expectedDiagnostics);
    }

    private static List<String> javaCommand(Class<?> mainClass) {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        return List.of(java, "-cp", System.getProperty("java.class.path"), mainClass.getName());
    }

    private static final class DelayedTerminationProcess extends Process {
        private final DelayedTerminationHandle handle;

        private DelayedTerminationProcess(DelayedTerminationHandle handle) {
            this.handle = handle;
        }

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() throws InterruptedException {
            while (handle.isAlive()) Thread.sleep(5);
            return 0;
        }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (handle.isAlive() && System.nanoTime() < deadline) Thread.sleep(5);
            return !handle.isAlive();
        }
        @Override public int exitValue() {
            if (handle.isAlive()) throw new IllegalThreadStateException("running");
            return 0;
        }
        @Override public void destroy() { handle.destroy(); }
        @Override public Process destroyForcibly() { handle.destroyForcibly(); return this; }
        @Override public boolean isAlive() { return handle.isAlive(); }
        @Override public ProcessHandle toHandle() { return handle; }
    }

    private static final class DelayedTerminationHandle implements ProcessHandle {
        private final long pid;
        private final List<ProcessHandle> descendants;
        private final long forcibleExitDelayMillis;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final CompletableFuture<ProcessHandle> exited = new CompletableFuture<>();

        private DelayedTerminationHandle(long pid, List<? extends ProcessHandle> descendants, boolean delayedForcibleExit) {
            this(pid, descendants, delayedForcibleExit ? 150 : 0);
        }

        private DelayedTerminationHandle(long pid, List<? extends ProcessHandle> descendants, long forcibleExitDelayMillis) {
            this.pid = pid;
            this.descendants = List.copyOf(descendants);
            this.forcibleExitDelayMillis = Math.max(0L, forcibleExitDelayMillis);
        }

        @Override public long pid() { return pid; }
        @Override public Optional<ProcessHandle> parent() { return Optional.empty(); }
        @Override public java.util.stream.Stream<ProcessHandle> children() { return descendants.stream(); }
        @Override public java.util.stream.Stream<ProcessHandle> descendants() { return descendants.stream(); }
        @Override public Info info() { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<ProcessHandle> onExit() { return exited; }
        @Override public boolean supportsNormalTermination() { return true; }
        @Override public boolean destroy() {
            if (forcibleExitDelayMillis == 0L) markExited();
            return true;
        }
        @Override public boolean destroyForcibly() {
            if (forcibleExitDelayMillis == 0L) {
                markExited();
            } else {
                Thread.startVirtualThread(() -> {
                    try { Thread.sleep(forcibleExitDelayMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    markExited();
                });
            }
            return true;
        }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public int compareTo(ProcessHandle other) { return Long.compare(pid, other.pid()); }
        @Override public int hashCode() { return Long.hashCode(pid); }
        @Override public boolean equals(Object other) {
            return other instanceof ProcessHandle handle && handle.pid() == pid;
        }

        private void markExited() {
            if (alive.compareAndSet(true, false)) exited.complete(this);
        }
    }

    public static class OutputFlood {
        public static void main(String[] args) throws Exception {
            byte[] chunk = "0123456789abcdef".repeat(512).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (int i = 0; i < 32; i++) {
                System.out.write(chunk);
                System.err.write(chunk);
            }
            System.out.flush();
            System.err.flush();
        }
    }

    public static class Sleeper {
        public static void main(String[] args) throws Exception {
            Thread.sleep(30_000);
        }
    }


    public static class SpawnChildAndSleep {
        public static void main(String[] args) throws Exception {
            String java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
            Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    Sleeper.class.getName()).start();
            Files.writeString(Path.of(args[0]), Long.toString(child.pid()));
            Thread.sleep(30_000);
        }
    }

    public static class EchoStdin {
        public static void main(String[] args) throws Exception {
            byte[] input = System.in.readAllBytes();
            System.out.print("len=" + input.length);
        }
    }


}
