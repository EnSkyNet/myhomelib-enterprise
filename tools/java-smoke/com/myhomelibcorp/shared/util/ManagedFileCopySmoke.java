package com.myhomelibcorp.shared.util;

import java.nio.file.*;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** Real production classes: no mocks for bytes, deletion, fallback or path validation. */
public final class ManagedFileCopySmoke {
    private static int checks;
    private static void check(boolean value, String why) {
        ++checks;
        if (!value) throw new AssertionError(why);
    }
    private static Path payload(Path path) throws IOException {
        byte[] block = new byte[1024 * 1024];
        for (int i = 0; i < block.length; i++) block[i] = (byte) (i * 31 + 17);
        try (var out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
            for (int i = 0; i < 12; i++) out.write(block);
        }
        return path;
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("mhl-copy-regression-");
        try {
            Path source = payload(root.resolve("original.fb2"));
            long originalLength = Files.size(source);
            AtomicInteger polls = new AtomicInteger();
            Path cancelled = root.resolve("cancelled.fb2");
            try {
                NoLinkPathSafety.copyFreshFile(source, cancelled, "large book", () -> polls.incrementAndGet() >= 5);
                throw new AssertionError("mid-stream cancel accepted");
            } catch (CancellationException expected) { /* expected */ }
            check(polls.get() >= 5, "copy cancellation was not polled");
            check(!Files.exists(cancelled), "partial copy was not removed");
            check(Files.size(source) == originalLength, "cancel mutated source");

            Path complete = root.resolve("full.fb2");
            NoLinkPathSafety.copyFreshFile(source, complete, "large book", () -> false);
            check(Files.mismatch(source, complete) == -1, "complete copy mismatch");
            try {
                NoLinkPathSafety.copyFreshFile(source, complete, "collision", () -> false);
                throw new AssertionError("collision replaced target");
            } catch (FileAlreadyExistsException expected) { /* expected */ }
            check(Files.mismatch(source, complete) == -1, "collision changed committed file");

            Path fallback = root.resolve("fallback.fb2");
            var unsupported = (SafeFilePrimitives.HardLinkCreator) (target, from) -> {
                throw new FileSystemException(target.toString(), null, "Operation not supported");
            };
            AtomicInteger fallbackPolls = new AtomicInteger();
            try {
                SafeFilePrimitives.publishFreshNoReplace(source, fallback, "fallback", unsupported, null,
                        () -> fallbackPolls.incrementAndGet() >= 5);
                throw new AssertionError("fallback cancel accepted");
            } catch (CancellationException expected) { /* expected */ }
            check(fallbackPolls.get() >= 5, "fallback did not poll cancellation");
            check(!Files.exists(fallback), "fallback left a partial published target");
            check(Files.size(source) == originalLength, "fallback changed source");
            boolean atomic = SafeFilePrimitives.publishFreshNoReplace(source, fallback, "fallback", unsupported, null,
                    () -> false);
            check(!atomic, "forced fallback was misreported as atomic");
            check(Files.mismatch(source, fallback) == -1, "fallback content mismatch");
            check(Files.isRegularFile(complete), "complete file disappeared");
            Path link = root.resolve("symbolic.fb2");
            try {
                Files.createSymbolicLink(link, source);
                try {
                    NoLinkPathSafety.copyFreshFile(link, root.resolve("unsafe.fb2"), "unsafe", () -> false);
                    throw new AssertionError("symlink copy accepted");
                } catch (IOException expected) { /* expected */ }
                check(!Files.exists(root.resolve("unsafe.fb2")), "symlink created output");
            } catch (UnsupportedOperationException | FileSystemException denied) {
                /* Some Windows environments have symlinks disabled. */
            }
            System.out.println("MANAGED_FILE_COPY_SMOKE: PASS " + checks + " assertions");
        } finally {
            NoLinkPathSafety.cleanupPrivateWorkspace(root, "copy smoke workspace");
        }
    }
}
