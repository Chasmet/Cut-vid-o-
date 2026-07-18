package com.chasmet.cutvideo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class VideoCollectionTest {

    @Test
    public void collectionSummarizesEveryNestedProcessingFolder() {
        SavedVideo first = video("clip_01.mp4", 12_000_000L);
        SavedVideo second = video("clip_02.mp4", 8_500_000L);
        SavedVideo third = video("clip_03.mp4", 4_000_000L);
        SavedVideoFolder firstFolder = new SavedVideoFolder(
                "jusqu-au-bout-1",
                Arrays.asList(first, second)
        );
        SavedVideoFolder secondFolder = new SavedVideoFolder(
                "jusqu-au-bout-2",
                Collections.singletonList(third)
        );

        VideoCollection collection = new VideoCollection(
                "collection-id",
                "Jusqu'au bout",
                123L,
                Arrays.asList(firstFolder, secondFolder)
        );

        assertEquals(2, collection.getFolderCount());
        assertEquals(3, collection.getVideoCount());
        assertEquals(24_500_000L, collection.getTotalSizeBytes());
        List<SavedVideo> allVideos = collection.getAllVideos();
        assertEquals(3, allVideos.size());
        assertSame(first, allVideos.get(0));
        assertSame(third, allVideos.get(2));
    }

    private SavedVideo video(String name, long sizeBytes) {
        return new SavedVideo(
                null,
                name,
                60_000L,
                sizeBytes,
                "Movies/CutVideo/test/",
                100L
        );
    }
}
