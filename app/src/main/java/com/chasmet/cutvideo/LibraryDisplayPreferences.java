package com.chasmet.cutvideo;

import android.content.Context;
import android.content.SharedPreferences;

public final class LibraryDisplayPreferences {

    private static final String PREFERENCES_NAME = "library_display";
    private static final String MODE_KEY = "mode";
    private static final String SIZE_KEY = "size";
    private static final String SORT_KEY = "sort";

    private LibraryDisplayPreferences() {
    }

    public static LibraryDisplaySettings get(Context context) {
        SharedPreferences preferences = preferences(context);
        return new LibraryDisplaySettings(
                preferences.getInt(MODE_KEY, LibraryDisplaySettings.MODE_LIST),
                preferences.getInt(SIZE_KEY, LibraryDisplaySettings.SIZE_NORMAL),
                preferences.getInt(SORT_KEY, LibraryDisplaySettings.SORT_RECENT)
        );
    }

    public static void save(Context context, LibraryDisplaySettings settings) {
        preferences(context).edit()
                .putInt(MODE_KEY, settings.getDisplayMode())
                .putInt(SIZE_KEY, settings.getItemSize())
                .putInt(SORT_KEY, settings.getSortMode())
                .apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
        );
    }
}
