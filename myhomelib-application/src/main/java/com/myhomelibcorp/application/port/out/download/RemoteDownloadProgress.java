package com.myhomelibcorp.application.port.out.download;

/**
 * Byte-level telemetry for one remote catalog package.
 * A {@code bytesTotal} value below zero means that the server did not expose a reliable total.
 */
public record RemoteDownloadProgress(
        long bytesProcessed,
        long bytesTotal,
        String currentItem,
        double fraction
) {
    public RemoteDownloadProgress {
        if (bytesProcessed < 0) bytesProcessed = 0;
        if (bytesTotal == 0) bytesTotal = -1;
        currentItem = currentItem == null ? "" : currentItem;
        fraction = Math.max(0.0, Math.min(1.0, fraction));
    }
}
