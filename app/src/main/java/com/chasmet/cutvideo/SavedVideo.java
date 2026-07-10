package com.chasmet.cutvideo;

import android.net.Uri;

public final class SavedVideo {
    private final Uri uri;
    private final String name;
    private final long durationMs;
    private final long sizeBytes;

    public SavedVideo(Uri uri, String name, long durationMs, long sizeBytes) {
        this.uri = uri;
        this.name = name;
        this.durationMs = durationMs;
        this.sizeBytes = sizeBytes;
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
}

