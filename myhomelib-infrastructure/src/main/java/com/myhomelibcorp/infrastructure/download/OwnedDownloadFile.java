package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.util.SafeFilePublication;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Ownership-bound access to predictable resumable-download files.
 *
 * <p>{@code NOFOLLOW_LINKS} alone only rejects symlink replacement. A regular file can still be
 * swapped between the initial size check and {@code APPEND}. This helper binds the open file handle
 * to a SHA-256 snapshot before any bytes are written and verifies after writing that the pathname
 * still names the bytes written through that handle.</p>
 */
final class OwnedDownloadFile {
    private OwnedDownloadFile() { }

    static Snapshot snapshot(Path path, String role) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Snapshot.absent();
        Path safe = SafeFilePublication.requireSafeRegularFile(path, role);
        long size = Files.size(safe);
        String hash = SafeFilePublication.sha256(safe, role + " snapshot");
        return new Snapshot(size, hash);
    }

    static Session open(Path path, Snapshot snapshot, boolean append, String role) throws IOException {
        if (append) {
            if (snapshot == null || !snapshot.exists()) {
                throw new IOException(role + " cannot append without an ownership snapshot: " + path);
            }
            FileChannel channel = SafeFilePublication.openOwnedAppendChannel(path, snapshot.sha256(), role);
            if (channel.size() != snapshot.size()) {
                channel.close();
                throw new IOException(role + " size changed before append: " + path);
            }
            return new Session(path, channel, role);
        }
        if (snapshot != null && snapshot.exists()) {
            throw new IOException(role + " already exists and was not safely detached: " + path);
        }
        return new Session(path, SafeFilePublication.openFreshReadWriteChannel(path, role), role);
    }

    static void removeIfOwned(Path path, Snapshot snapshot, String role) throws IOException {
        if (snapshot == null || !snapshot.exists()) return;
        SafeFilePublication.removeIfOwned(path, snapshot.sha256(), role);
    }

    record Snapshot(long size, String sha256) {
        static Snapshot absent() { return new Snapshot(0L, null); }
        boolean exists() { return sha256 != null && !sha256.isBlank(); }
    }

    static final class Session implements AutoCloseable {
        private final Path path;
        private final FileChannel channel;
        private final String role;
        private boolean finished;

        private Session(Path path, FileChannel channel, String role) {
            this.path = path;
            this.channel = channel;
            this.role = role;
        }

        OutputStream outputStream() {
            return Channels.newOutputStream(channel);
        }

        long size() throws IOException { return channel.size(); }

        Snapshot finish() throws IOException {
            if (finished) throw new IllegalStateException("Download file session is already finished");
            long size;
            String handleHash;
            try {
                channel.force(true);
                size = channel.size();
                handleHash = SafeFilePublication.sha256(channel, role + " open handle");
            } finally {
                finished = true;
                channel.close();
            }
            SafeFilePublication.requireOwnedPath(path, handleHash, role + " pathname verification");
            long pathSize = Files.size(SafeFilePublication.requireSafeRegularFile(path, role + " pathname"));
            if (pathSize != size) {
                throw new IOException(role + " size changed after write: " + path);
            }
            return new Snapshot(size, handleHash);
        }

        @Override
        public void close() throws IOException {
            if (!finished) {
                finished = true;
                channel.close();
            }
        }
    }
}
