package com.chasmet.cutvideo;

public final class VideoMetadata {

    private final String title;
    private final String description;
    private final String hashtags;

    public VideoMetadata(String title, String description, String hashtags) {
        this.title = safe(title);
        this.description = safe(description);
        this.hashtags = safe(hashtags);
    }

    public static VideoMetadata fromSchedule(PublicationSchedule schedule) {
        if (schedule == null) {
            return new VideoMetadata("", "", "");
        }
        return new VideoMetadata(
                schedule.getTitle(),
                schedule.getDescription(),
                schedule.getHashtags()
        );
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getHashtags() {
        return hashtags;
    }

    public boolean isEmpty() {
        return title.isEmpty() && description.isEmpty() && hashtags.isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
