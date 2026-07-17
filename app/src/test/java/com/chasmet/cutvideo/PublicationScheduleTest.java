package com.chasmet.cutvideo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PublicationScheduleTest {

    @Test
    public void buildsMetadataInPublishOrder() {
        PublicationSchedule schedule = schedule(
                "Titre",
                "Description",
                "#video #test"
        );

        assertEquals(
                "Titre\n\nDescription\n\n#video #test",
                schedule.buildShareText()
        );
    }

    @Test
    public void publishedCopyKeepsAllPlanningDetails() {
        PublicationSchedule original = schedule("Titre", "", "#test");
        PublicationSchedule published = original.withPublished(true);

        assertFalse(original.isPublished());
        assertTrue(published.isPublished());
        assertEquals(original.getId(), published.getId());
        assertEquals(original.getScheduledAtMillis(), published.getScheduledAtMillis());
        assertEquals(original.getPlatformKey(), published.getPlatformKey());
        assertEquals(original.getTitle(), published.getTitle());
        assertEquals(original.getDescription(), published.getDescription());
        assertEquals(original.getHashtags(), published.getHashtags());
    }

    @Test
    public void unknownPlatformFallsBackToOtherApplication() {
        PublicationSchedule schedule = new PublicationSchedule(
                "id",
                "content://video/1",
                "video.mp4",
                "network-that-does-not-exist",
                1_000L,
                "",
                "",
                "",
                PublicationSchedule.VISIBILITY_PUBLIC,
                500L,
                false
        );

        assertEquals(SocialPlatform.OTHER, schedule.getPlatform());
    }

    private PublicationSchedule schedule(String title, String description, String hashtags) {
        return new PublicationSchedule(
                "schedule-id",
                "content://video/1",
                "video.mp4",
                SocialPlatform.INSTAGRAM.getKey(),
                2_000L,
                title,
                description,
                hashtags,
                PublicationSchedule.VISIBILITY_PUBLIC,
                1_000L,
                false
        );
    }
}
