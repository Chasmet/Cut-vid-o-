package com.chasmet.cutvideo;

public final class PublicationSchedule {

    public static final String VISIBILITY_PUBLIC = "public";
    public static final String VISIBILITY_UNLISTED = "unlisted";
    public static final String VISIBILITY_PRIVATE = "private";

    private final String id;
    private final String videoUri;
    private final String videoName;
    private final String platformKey;
    private final long scheduledAtMillis;
    private final String title;
    private final String description;
    private final String hashtags;
    private final String visibility;
    private final long createdAtMillis;
    private final boolean published;

    public PublicationSchedule(
            String id,
            String videoUri,
            String videoName,
            String platformKey,
            long scheduledAtMillis,
            String title,
            String description,
            String hashtags,
            String visibility,
            long createdAtMillis,
            boolean published
    ) {
        this.id = safe(id);
        this.videoUri = safe(videoUri);
        this.videoName = safe(videoName);
        this.platformKey = SocialPlatform.fromKey(platformKey).getKey();
        this.scheduledAtMillis = scheduledAtMillis;
        this.title = safe(title);
        this.description = safe(description);
        this.hashtags = safe(hashtags);
        this.visibility = normalizeVisibility(visibility);
        this.createdAtMillis = createdAtMillis;
        this.published = published;
    }

    public String getId() {
        return id;
    }

    public String getVideoUri() {
        return videoUri;
    }

    public String getVideoName() {
        return videoName;
    }

    public String getPlatformKey() {
        return platformKey;
    }

    public SocialPlatform getPlatform() {
        return SocialPlatform.fromKey(platformKey);
    }

    public long getScheduledAtMillis() {
        return scheduledAtMillis;
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

    public String getVisibility() {
        return visibility;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public boolean isPublished() {
        return published;
    }

    public PublicationSchedule withPublished(boolean newPublished) {
        return new PublicationSchedule(
                id,
                videoUri,
                videoName,
                platformKey,
                scheduledAtMillis,
                title,
                description,
                hashtags,
                visibility,
                createdAtMillis,
                newPublished
        );
    }

    public PublicationSchedule withVideoName(String newVideoName) {
        return new PublicationSchedule(
                id,
                videoUri,
                newVideoName,
                platformKey,
                scheduledAtMillis,
                title,
                description,
                hashtags,
                visibility,
                createdAtMillis,
                published
        );
    }

    public String buildShareText() {
        StringBuilder result = new StringBuilder();
        appendSection(result, title);
        appendSection(result, description);
        appendSection(result, hashtags);
        return result.toString();
    }

    private static void appendSection(StringBuilder result, String value) {
        String normalized = safe(value);
        if (normalized.isEmpty()) {
            return;
        }
        if (result.length() > 0) {
            result.append("\n\n");
        }
        result.append(normalized);
    }

    private static String normalizeVisibility(String value) {
        if (VISIBILITY_UNLISTED.equals(value) || VISIBILITY_PRIVATE.equals(value)) {
            return value;
        }
        return VISIBILITY_PUBLIC;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
