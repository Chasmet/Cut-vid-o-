package com.chasmet.cutvideo;

import android.net.Uri;

public final class SavedVideo {
    private final Uri uri;
    private final String name;
    private final long durationMs;
    private final long sizeBytes;
    private final String relativePath;
    private final long dateAddedSeconds;

    public SavedVideo(
            Uri uri,
            String name,
            long durationMs,
            long sizeBytes,
            String relativePath,
            long dateAddedSeconds
    ) {
        this.uri = uri;
        this.name = name;
        this.durationMs = durationMs;
        this.sizeBytes = sizeBytes;
        this.relativePath = relativePath;
        this.dateAddedSeconds = dateAddedSeconds;
    }

    public Uri getUri() {
        return uri;
    }

    public String getName() {
        return name;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public long getDateAddedSeconds() {
        return dateAddedSeconds;
    }
}
