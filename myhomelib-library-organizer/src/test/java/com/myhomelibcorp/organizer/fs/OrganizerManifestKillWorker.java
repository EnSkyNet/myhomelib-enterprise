package com.myhomelibcorp.organizer.fs;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Dedicated test process: simulates a hard crash after a durable partial event frame. */
public final class OrganizerManifestKillWorker {
    private OrganizerManifestKillWorker() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected event log path");
        try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ByteBuffer frame = StandardCharsets.UTF_8.encode("f".repeat(64) + "\t{\"type\":\"operation\"");
            while (frame.hasRemaining()) channel.write(frame);
            channel.force(true);
        }
        Runtime.getRuntime().halt(83);
    }
}
