package com.chasmet.cutvideo;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class LibrarySorter {

    private LibrarySorter() {
    }

    public static List<VideoCollection> collections(
            List<VideoCollection> source,
            int sortMode
    ) {
        List<VideoCollection> result = new ArrayList<>(source);
        Comparator<VideoCollection> comparator;
        switch (sortMode) {
            case LibraryDisplaySettings.SORT_NAME:
                comparator = (first, second) -> compareNames(first.getName(), second.getName());
                break;
            case LibraryDisplaySettings.SORT_SIZE:
                comparator = Comparator.comparingLong(VideoCollection::getTotalSizeBytes).reversed();
                break;
            case LibraryDisplaySettings.SORT_COUNT:
                comparator = Comparator.comparingInt(VideoCollection::getVideoCount).reversed();
                break;
            case LibraryDisplaySettings.SORT_RECENT:
            default:
                comparator = Comparator.comparingLong(VideoCollection::getCreatedAtMillis).reversed();
                break;
        }
        result.sort(comparator.thenComparing(VideoCollection::getName, LibrarySorter::compareNames));
        return result;
    }

    public static List<SavedVideoFolder> folders(
            List<SavedVideoFolder> source,
            int sortMode
    ) {
        List<SavedVideoFolder> result = new ArrayList<>(source);
        Comparator<SavedVideoFolder> comparator;
        switch (sortMode) {
            case LibraryDisplaySettings.SORT_NAME:
                comparator = (first, second) -> compareNames(
                        VideoFolderUtils.displayName(first.getKey()),
                        VideoFolderUtils.displayName(second.getKey())
                );
                break;
            case LibraryDisplaySettings.SORT_SIZE:
                comparator = Comparator.comparingLong(SavedVideoFolder::getTotalSizeBytes).reversed();
                break;
            case LibraryDisplaySettings.SORT_COUNT:
                comparator = Comparator.comparingInt(SavedVideoFolder::getVideoCount).reversed();
                break;
            case LibraryDisplaySettings.SORT_RECENT:
            default:
                comparator = Comparator.comparingLong(
                        SavedVideoFolder::getLatestDateAddedSeconds
                ).reversed();
                break;
        }
        result.sort(comparator.thenComparing(
                folder -> VideoFolderUtils.displayName(folder.getKey()),
                LibrarySorter::compareNames
        ));
        return result;
    }

    public static List<SavedVideo> videos(List<SavedVideo> source, int sortMode) {
        List<SavedVideo> result = new ArrayList<>(source);
        Comparator<SavedVideo> comparator;
        switch (sortMode) {
            case LibraryDisplaySettings.SORT_NAME:
                comparator = (first, second) -> compareNames(first.getName(), second.getName());
                break;
            case LibraryDisplaySettings.SORT_SIZE:
                comparator = Comparator.comparingLong(SavedVideo::getSizeBytes).reversed();
                break;
            case LibraryDisplaySettings.SORT_COUNT:
            case LibraryDisplaySettings.SORT_RECENT:
            default:
                comparator = Comparator.comparingLong(SavedVideo::getDateAddedSeconds).reversed();
                break;
        }
        result.sort(comparator.thenComparing(SavedVideo::getName, LibrarySorter::compareNames));
        return result;
    }

    private static int compareNames(String first, String second) {
        Collator collator = Collator.getInstance(Locale.FRANCE);
        collator.setStrength(Collator.PRIMARY);
        return collator.compare(first == null ? "" : first, second == null ? "" : second);
    }
}
