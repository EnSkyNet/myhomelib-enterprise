package com.myhomelibcorp.shared.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes native processes without the classic stdout/stderr pipe deadlock.
 *
 * <p>Both process streams are drained concurrently from the moment the process starts. Output is
 * bounded in memory while excess bytes are still consumed so the child process can never block on
 * a full parent pipe. Timeouts terminate the complete descendant process tree where the platform
 * allows it.</p>
 */
public final class ProcessExecutionSupport {
    public static final int DEFAULT_MAX_CAPTURE_BYTES = 256 * 1024;
    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(2);
    private static final Duration OUTPUT_DRAIN_GRACE = Duration.ofSeconds(2);

    private ProcessExecutionSupport() { }

    public static Result run(List<String> command, String stdin, Duration timeout, int maxCaptureBytes)
            throws IOException, InterruptedException {
        Objects.requireNonNull(command, "command");
        if (command.isEmpty()) throw new IllegalArgumentException("command cannot be empty");
        return run(new ProcessBuilder(new ArrayList<>(command)),
                stdin == null ? null : stdin.getBytes(StandardCharsets.UTF_8), timeout, maxCaptureBytes);
    }

    public static Result run(ProcessBuilder builder, byte[] stdin, Duration timeout, int maxCaptureBytes)
            throws IOException, InterruptedException {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        if (maxCaptureBytes <= 0) throw new IllegalArgumentException("maxCaptureBytes must be positive");

        // Separate streams are intentional: callers often need stderr diagnostics. Each one is
        // drained concurrently, so keeping them separate does not reintroduce a pipe deadlock.
        builder.redirectErrorStream(false);
        Process process = builder.start();
        ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
        Future<CapturedStream> stdout = io.submit(() -> drain(process.getInputStream(), maxCaptureBytes));
        Future<CapturedStream> stderr = io.submit(() -> drain(process.getErrorStream(), maxCaptureBytes));
        Future<?> stdinWriter = io.submit(() -> {
            try (OutputStream out = process.getOutputStream()) {
                if (stdin != null && stdin.length > 0) out.write(stdin);
                out.flush();
            }
            return null;
        });

        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminateTree(process);
                awaitTermination(process);
                throw new ProcessTimeoutException("Process timed out after " + timeout.toMillis() + " ms");
            }

            awaitStdin(stdinWriter);
            CapturedStream out = awaitCaptured(stdout, process.getInputStream());
            CapturedStream err = awaitCaptured(stderr, process.getErrorStream());
            return new Result(process.exitValue(), out.bytes(), err.bytes(), out.truncated(), err.truncated());
        } catch (InterruptedException e) {
            terminateTree(process);
            awaitTerminationUninterruptibly(process);
            throw e;
        } finally {
            if (process.isAlive()) terminateTree(process);
            stdout.cancel(true);
            stderr.cancel(true);
            stdinWriter.cancel(true);
            io.shutdownNow();
        }
    }

    private static CapturedStream drain(InputStream input, int maxCaptureBytes) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream captured = new ByteArrayOutputStream(Math.min(maxCaptureBytes, 8192))) {
            byte[] buffer = new byte[8192];
            int kept = 0;
            boolean truncated = false;
            for (int read; (read = in.read(buffer)) >= 0;) {
                if (read == 0) continue;
                int remaining = maxCaptureBytes - kept;
                if (remaining > 0) {
                    int copy = Math.min(remaining, read);
                    captured.write(buffer, 0, copy);
                    kept += copy;
                    if (copy < read) truncated = true;
                } else {
                    truncated = true;
                }
            }
            return new CapturedStream(captured.toByteArray(), truncated);
        }
    }

    private static CapturedStream awaitCaptured(Future<CapturedStream> future, InputStream stream)
            throws IOException, InterruptedException {
        try {
            return future.get(OUTPUT_DRAIN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            try { stream.close(); } catch (IOException ignored) { }
            future.cancel(true);
            throw new IOException("Process output did not close after process termination", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IOException("Cannot read process output", cause);
        }
    }

    private static void awaitStdin(Future<?> future) throws IOException, InterruptedException {
        try {
            future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IOException("Cannot write process input", cause);
        }
    }

    private static void terminateTree(Process process) {
        ProcessHandle handle = process.toHandle();
        List<ProcessHandle> descendants;
        try { descendants = handle.descendants().toList(); }
        catch (RuntimeException ignored) { descendants = List.of(); }
        // Capture descendants before terminating the parent; after parent exit they may no longer
        // be discoverable via ProcessHandle.descendants().
        descendants.forEach(child -> {
            try { child.destroy(); } catch (RuntimeException ignored) { }
        });
        try { handle.destroy(); } catch (RuntimeException ignored) { }
        descendants.forEach(child -> {
            if (child.isAlive()) {
                try { child.destroyForcibly(); } catch (RuntimeException ignored) { }
            }
        });
        if (process.isAlive()) {
            try { handle.destroyForcibly(); } catch (RuntimeException ignored) { }
        }
    }

    private static void awaitTermination(Process process) throws InterruptedException {
        process.waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void awaitTerminationUninterruptibly(Process process) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TERMINATION_GRACE.toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            try {
                process.waitFor(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private record CapturedStream(byte[] bytes, boolean truncated) { }

    public record Result(int exitCode, byte[] stdout, byte[] stderr, boolean stdoutTruncated, boolean stderrTruncated) {
        public String stdoutText() { return stdoutText(StandardCharsets.UTF_8); }
        public String stderrText() { return stderrText(StandardCharsets.UTF_8); }
        public String stdoutText(Charset charset) { return new String(stdout, charset); }
        public String stderrText(Charset charset) { return new String(stderr, charset); }
    }

    public static final class ProcessTimeoutException extends IOException {
        public ProcessTimeoutException(String message) { super(message); }
    }
}
