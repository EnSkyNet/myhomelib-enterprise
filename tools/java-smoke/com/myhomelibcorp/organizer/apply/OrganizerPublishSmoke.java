package com.myhomelibcorp.organizer.apply;

import java.nio.file.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

public final class OrganizerPublishSmoke {
    private static int checks;
    private static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("mhl-publish-regression-");
        try {
            Path staged = root.resolve("stage.fb2");
            byte[] data = new byte[1024 * 1024];
            for (int i = 0; i < data.length; i++) data[i] = (byte) i;
            try (var out = Files.newOutputStream(staged, StandardOpenOption.CREATE_NEW)) {
                for (int i = 0; i < 12; i++) out.write(data);
            }
            String hash = OrganizerFileIntegritySupport.sha256(staged);
            var publisher = new OrganizerTargetPublisher();
            Path target = root.resolve("dest/book.fb2");
            AtomicInteger polls = new AtomicInteger();
            try {
                publisher.publish(staged, target, hash, () -> polls.incrementAndGet() >= 5);
                throw new AssertionError("publisher ignored cancellation");
            } catch (CancellationException expected) { /* expected */ }
            check(polls.get() >= 5, "publisher never observed cancellation");
            check(!Files.exists(target), "cancelled publisher exposed target");
            try (var files = Files.list(target.getParent())) {
                check(files.noneMatch(p -> p.getFileName().toString().startsWith(".mhl-tmp-")),
                        "temporary publication not cleaned");
            }
            check(Files.exists(staged), "cancelled publisher removed source");
            publisher.publish(staged, target, hash, () -> false);
            check(Files.mismatch(staged, target) == -1, "published bytes mismatch");
            try {
                publisher.publish(staged, target, hash, () -> false);
                throw new AssertionError("existing target overwritten");
            } catch (FileAlreadyExistsException expected) { /* expected */ }
            check(Files.mismatch(staged, target) == -1, "collision damaged published target");
            try {
                publisher.publish(staged, root.resolve("wrong.fb2"), "0".repeat(64), () -> false);
                throw new AssertionError("wrong digest accepted");
            } catch (IllegalStateException expected) { /* expected */ }
            check(!Files.exists(root.resolve("wrong.fb2")), "wrong hash published a file");
            System.out.println("ORGANIZER_PUBLISH_SMOKE: PASS " + checks + " assertions");
        } finally {
            com.myhomelibcorp.organizer.fs.OrganizerPathSafety.createDirectoriesNoLinks(root, "smoke root");
            java.nio.file.Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                        throws java.io.IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path dir, java.io.IOException exc)
                        throws java.io.IOException { if (exc != null) throw exc; Files.delete(dir); return FileVisitResult.CONTINUE; }
            });
        }
    }
}
