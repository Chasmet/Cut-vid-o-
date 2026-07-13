package com.chasmet.cutvideo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

public final class SavedVideoFolderTest {

    @Test
    public void folderSummarizesFilesSizeAndLatestDate() {
        SavedVideo first = new SavedVideo(
                null,
                "morceau_01.mp4",
                60_000L,
                10_000_000L,
                "Movies/CutVideo/test/",
                100L
        );
        SavedVideo second = new SavedVideo(
                null,
                "morceau_02.mp4",
                30_000L,
                5_500_000L,
                "Movies/CutVideo/test/",
                200L
        );

        SavedVideoFolder folder = new SavedVideoFolder(
                "test",
                Arrays.asList(first, second)
        );

        assertEquals(2, folder.getVideoCount());
        assertEquals(15_500_000L, folder.getTotalSizeBytes());
        assertEquals(200L, folder.getLatestDateAddedSeconds());
    }
}
