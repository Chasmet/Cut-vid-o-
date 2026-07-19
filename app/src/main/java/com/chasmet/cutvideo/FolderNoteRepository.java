package com.chasmet.cutvideo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

public final class FolderNoteRepository {

    public static final int MAX_NOTE_LENGTH = 6_000;

    private static final String PREFERENCES_NAME = "folder_notes";
    private static final String COLLECTION_PREFIX = "collection|";
    private static final String FOLDER_PREFIX = "folder|";

    private FolderNoteRepository() {
    }

    public static FolderNote getCollection(Context context, String collectionId) {
        return read(context, COLLECTION_PREFIX + safeKey(collectionId));
    }

    public static FolderNote getFolder(Context context, String folderKey) {
        return read(context, FOLDER_PREFIX + safeKey(folderKey));
    }

    public static void saveCollection(Context context, String collectionId, String text) {
        write(context, COLLECTION_PREFIX + safeKey(collectionId), text);
    }

    public static void saveFolder(Context context, String folderKey, String text) {
        write(context, FOLDER_PREFIX + safeKey(folderKey), text);
    }

    public static void deleteCollection(Context context, String collectionId) {
        preferences(context).edit()
                .remove(COLLECTION_PREFIX + safeKey(collectionId))
                .apply();
    }

    public static void deleteFolder(Context context, String folderKey) {
        preferences(context).edit()
                .remove(FOLDER_PREFIX + safeKey(folderKey))
                .apply();
    }

    public static void updateFolderKey(
            Context context,
            String oldFolderKey,
            String newFolderKey
    ) {
        String oldKey = FOLDER_PREFIX + safeKey(oldFolderKey);
        String newKey = FOLDER_PREFIX + safeKey(newFolderKey);
        SharedPreferences preferences = preferences(context);
        String serialized = preferences.getString(oldKey, null);
        if (serialized == null) {
            return;
        }
        preferences.edit()
                .remove(oldKey)
                .putString(newKey, serialized)
                .apply();
    }

    static String normalizeText(String value) {
        String source = value == null ? "" : value.replace("\r\n", "\n");
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '\n' || character == '\t' || !Character.isISOControl(character)) {
                normalized.append(character);
            }
            if (normalized.length() >= MAX_NOTE_LENGTH) {
                break;
            }
        }
        return normalized.toString().trim();
    }

    private static FolderNote read(Context context, String key) {
        String serialized = preferences(context).getString(key, null);
        if (serialized == null || serialized.isEmpty()) {
            return new FolderNote("", 0L);
        }
        try {
            JSONObject item = new JSONObject(serialized);
            return new FolderNote(
                    normalizeText(item.optString("text", "")),
                    item.optLong("updatedAt", 0L)
            );
        } catch (JSONException ignored) {
            return new FolderNote("", 0L);
        }
    }

    private static void write(Context context, String key, String value) {
        String text = normalizeText(value);
        if (text.isEmpty()) {
            preferences(context).edit().remove(key).apply();
            return;
        }
        JSONObject item = new JSONObject();
        try {
            item.put("text", text);
            item.put("updatedAt", System.currentTimeMillis());
        } catch (JSONException ignored) {
            return;
        }
        preferences(context).edit().putString(key, item.toString()).apply();
    }

    private static String safeKey(String value) {
        return value == null ? "" : value;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
        );
    }
}
