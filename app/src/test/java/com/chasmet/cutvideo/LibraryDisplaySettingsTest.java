package com.chasmet.cutvideo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LibraryDisplaySettingsTest {

    @Test
    public void defaultsUseDetailedListAndRecentOrder() {
        LibraryDisplaySettings settings = LibraryDisplaySettings.defaults();

        assertFalse(settings.usesGrid());
        assertEquals(LibraryDisplaySettings.SIZE_NORMAL, settings.getItemSize());
        assertEquals(LibraryDisplaySettings.SORT_RECENT, settings.getSortMode());
    }

    @Test
    public void invalidStoredValuesFallBackToSafeDefaults() {
        LibraryDisplaySettings settings = new LibraryDisplaySettings(99, -4, 12);

        assertEquals(LibraryDisplaySettings.MODE_LIST, settings.getDisplayMode());
        assertEquals(LibraryDisplaySettings.SIZE_NORMAL, settings.getItemSize());
        assertEquals(LibraryDisplaySettings.SORT_RECENT, settings.getSortMode());
    }

    @Test
    public void gridSizeControlsColumnCount() {
        assertEquals(3, grid(LibraryDisplaySettings.SIZE_SMALL).gridSpanCount());
        assertEquals(2, grid(LibraryDisplaySettings.SIZE_NORMAL).gridSpanCount());
        assertEquals(1, grid(LibraryDisplaySettings.SIZE_LARGE).gridSpanCount());
        assertTrue(grid(LibraryDisplaySettings.SIZE_SMALL).usesGrid());
    }

    private LibraryDisplaySettings grid(int itemSize) {
        return new LibraryDisplaySettings(
                LibraryDisplaySettings.MODE_GRID,
                itemSize,
                LibraryDisplaySettings.SORT_RECENT
        );
    }
}
