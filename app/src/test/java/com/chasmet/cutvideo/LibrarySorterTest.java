package com.chasmet.cutvideo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class LibrarySorterTest {

    @Test
    public void foldersCanBeSortedByNameSizeCountAndDate() {
        SavedVideoFolder beta = folder(
                "Beta",
                video("b1.mp4", 200L, 20L),
                video("b2.mp4", 100L, 30L)
        );
        SavedVideoFolder alpha = folder("Alpha", video("a.mp4", 500L, 10L));
        List<SavedVideoFolder> folders = Arrays.asList(beta, alpha);

        assertEquals("Alpha", LibrarySorter.folders(
                folders,
                LibraryDisplaySettings.SORT_NAME
        ).get(0).getKey());
        assertEquals("Alpha", LibrarySorter.folders(
                folders,
                LibraryDisplaySettings.SORT_SIZE
        ).get(0).getKey());
        assertEquals("Beta", LibrarySorter.folders(
                folders,
                LibraryDisplaySettings.SORT_COUNT
        ).get(0).getKey());
        assertEquals("Beta", LibrarySorter.folders(
                folders,
                LibraryDisplaySettings.SORT_RECENT
        ).get(0).getKey());
    }

    @Test
    public void collectionsCanBeSortedByCreationAndVideoCount() {
        VideoCollection olderLarge = new VideoCollection(
                "older",
                "Ancien",
                10L,
                Collections.singletonList(folder(
                        "Ancien",
                        video("1.mp4", 10L, 1L),
                        video("2.mp4", 10L, 2L)
                ))
        );
        VideoCollection newerSmall = new VideoCollection(
                "newer",
                "Nouveau",
                20L,
                Collections.singletonList(folder(
                        "Nouveau",
                        video("3.mp4", 10L, 3L)
                ))
        );

        assertEquals("newer", LibrarySorter.collections(
                Arrays.asList(olderLarge, newerSmall),
                LibraryDisplaySettings.SORT_RECENT
        ).get(0).getId());
        assertEquals("older", LibrarySorter.collections(
                Arrays.asList(olderLarge, newerSmall),
                LibraryDisplaySettings.SORT_COUNT
        ).get(0).getId());
    }

    @Test
    public void videosUseTheSelectedOrderWithoutChangingTheSource() {
        SavedVideo laterSmall = video("z.mp4", 50L, 20L);
        SavedVideo earlierLarge = video("a.mp4", 500L, 10L);
        List<SavedVideo> source = Arrays.asList(laterSmall, earlierLarge);

        assertEquals("a.mp4", LibrarySorter.videos(
                source,
                LibraryDisplaySettings.SORT_NAME
        ).get(0).getName());
        assertEquals("a.mp4", LibrarySorter.videos(
                source,
                LibraryDisplaySettings.SORT_SIZE
        ).get(0).getName());
        assertEquals("z.mp4", LibrarySorter.videos(
                source,
                LibraryDisplaySettings.SORT_RECENT
        ).get(0).getName());
        assertEquals("z.mp4", source.get(0).getName());
    }

    private SavedVideoFolder folder(String key, SavedVideo... videos) {
        return new SavedVideoFolder(key, Arrays.asList(videos));
    }

    private SavedVideo video(String name, long sizeBytes, long dateAddedSeconds) {
        return new SavedVideo(
                null,
                name,
                60_000L,
                sizeBytes,
                "Movies/CutVideo/test/",
                dateAddedSeconds
        );
    }
}
