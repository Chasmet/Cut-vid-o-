package com.chasmet.cutvideo;

public final class LibraryDisplaySettings {

    public static final int MODE_LIST = 0;
    public static final int MODE_GRID = 1;

    public static final int SIZE_SMALL = 0;
    public static final int SIZE_NORMAL = 1;
    public static final int SIZE_LARGE = 2;

    public static final int SORT_RECENT = 0;
    public static final int SORT_NAME = 1;
    public static final int SORT_SIZE = 2;
    public static final int SORT_COUNT = 3;

    private final int displayMode;
    private final int itemSize;
    private final int sortMode;

    public LibraryDisplaySettings(int displayMode, int itemSize, int sortMode) {
        this.displayMode = validDisplayMode(displayMode) ? displayMode : MODE_LIST;
        this.itemSize = validItemSize(itemSize) ? itemSize : SIZE_NORMAL;
        this.sortMode = validSortMode(sortMode) ? sortMode : SORT_RECENT;
    }

    public static LibraryDisplaySettings defaults() {
        return new LibraryDisplaySettings(MODE_LIST, SIZE_NORMAL, SORT_RECENT);
    }

    public int getDisplayMode() {
        return displayMode;
    }

    public int getItemSize() {
        return itemSize;
    }

    public int getSortMode() {
        return sortMode;
    }

    public boolean usesGrid() {
        return displayMode == MODE_GRID;
    }

    public int gridSpanCount() {
        if (itemSize == SIZE_SMALL) {
            return 3;
        }
        if (itemSize == SIZE_LARGE) {
            return 1;
        }
        return 2;
    }

    private static boolean validDisplayMode(int value) {
        return value == MODE_LIST || value == MODE_GRID;
    }

    private static boolean validItemSize(int value) {
        return value >= SIZE_SMALL && value <= SIZE_LARGE;
    }

    private static boolean validSortMode(int value) {
        return value >= SORT_RECENT && value <= SORT_COUNT;
    }
}
