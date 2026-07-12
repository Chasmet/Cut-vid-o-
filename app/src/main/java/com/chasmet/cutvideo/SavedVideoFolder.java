package com.chasmet.cutvideo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SavedVideoFolder {
    private final String key;
    private final List<SavedVideo> videos;

    public SavedVideoFolder(String key, List<SavedVideo> videos) {
        this.key = key;
        this.videos = Collections.unmodifiableList(new ArrayList<>(videos));
    }

    public String getKey() {
        return key;
    }

    public List<SavedVideo> getVideos() {
        return videos;
    }

    public int getVideoCount() {
        return videos.size();
    }

    public long getTotalSizeBytes() {
        long total = 0L;
        for (SavedVideo video : videos) {
            total += Math.max(0L, video.getSizeBytes());
        }
        return total;
    }

    public long getLatestDateAddedSeconds() {
        long latest = 0L;
        for (SavedVideo video : videos) {
            latest = Math.max(latest, video.getDateAddedSeconds());
        }
        return latest;
    }
}

