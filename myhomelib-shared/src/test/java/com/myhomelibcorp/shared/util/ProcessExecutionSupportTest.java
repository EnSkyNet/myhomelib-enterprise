package com.myhomelibcorp.shared.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessExecutionSupportTest {

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

        assertThatThrownBy(() -> ProcessExecutionSupport.run(command, null, Duration.ofMillis(500), 4096))
                .isInstanceOf(ProcessExecutionSupport.ProcessTimeoutException.class);

        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(25);
        assertThat(pidFile).exists();
        long childPid = Long.parseLong(Files.readString(pidFile).trim());
        while (ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        Files.deleteIfExists(pidFile);
    }

    @Test
    void writesStdinAndCapturesResponse() throws Exception {
        var result = ProcessExecutionSupport.run(javaCommand(EchoStdin.class), "secret-value", Duration.ofSeconds(5), 4096);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdoutText()).isEqualTo("len=12");
        assertThat(result.stderrText()).isEmpty();
    }

    private static List<String> javaCommand(Class<?> mainClass) {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        return List.of(java, "-cp", System.getProperty("java.class.path"), mainClass.getName());
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
