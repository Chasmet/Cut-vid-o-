package com.chasmet.cutvideo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VideoMetadataTest {

    @Test
    public void keepsAllMetadataFromPublishedSchedule() {
        PublicationSchedule schedule = new PublicationSchedule(
                "id",
                "content://video/1",
                "video.mp4",
                SocialPlatform.YOUTUBE.getKey(),
                2_000L,
                "Mon titre",
                "Ma description",
                "#Video #Test",
                PublicationSchedule.VISIBILITY_PUBLIC,
                1_000L,
                true
        );

        VideoMetadata metadata = VideoMetadata.fromSchedule(schedule);

        assertFalse(metadata.isEmpty());
        assertEquals("Mon titre", metadata.getTitle());
        assertEquals("Ma description", metadata.getDescription());
        assertEquals("#Video #Test", metadata.getHashtags());
    }

    @Test
    public void whitespaceOnlyMetadataIsEmpty() {
        assertTrue(new VideoMetadata("  ", "\n", null).isEmpty());
    }
}
