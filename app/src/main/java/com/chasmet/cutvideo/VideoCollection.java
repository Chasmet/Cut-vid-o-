package com.chasmet.cutvideo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class VideoCollection {

    private final String id;
    private final String name;
    private final long createdAtMillis;
    private final List<SavedVideoFolder> folders;

    public VideoCollection(
            String id,
            String name,
            long createdAtMillis,
            List<SavedVideoFolder> folders
    ) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.createdAtMillis = createdAtMillis;
        this.folders = Collections.unmodifiableList(new ArrayList<>(folders));
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public List<SavedVideoFolder> getFolders() {
        return folders;
    }

    public int getFolderCount() {
        return folders.size();
    }

    public int getVideoCount() {
        int count = 0;
        for (SavedVideoFolder folder : folders) {
            count += folder.getVideoCount();
        }
        return count;
    }

    public long getTotalSizeBytes() {
        long total = 0L;
        for (SavedVideoFolder folder : folders) {
            total += folder.getTotalSizeBytes();
        }
        return total;
    }

    public List<SavedVideo> getAllVideos() {
        List<SavedVideo> videos = new ArrayList<>();
        for (SavedVideoFolder folder : folders) {
            videos.addAll(folder.getVideos());
        }
        return videos;
    }
}
