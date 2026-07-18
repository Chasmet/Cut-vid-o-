package com.chasmet.cutvideo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class VideoCollectionRepository {

    private static final String PREFERENCES_NAME = "video_collections";
    private static final String COLLECTIONS_KEY = "collections";
    private static final String ASSIGNMENTS_KEY = "folder_assignments";

    private VideoCollectionRepository() {
    }

    public static synchronized List<VideoCollection> list(
            Context context,
            List<SavedVideoFolder> folders
    ) {
        List<CollectionRecord> records = readCollections(context);
        JSONObject assignments = readAssignments(context);
        Map<String, List<SavedVideoFolder>> grouped = new LinkedHashMap<>();
        for (CollectionRecord record : records) {
            grouped.put(record.id, new ArrayList<>());
        }
        for (SavedVideoFolder folder : folders) {
            String collectionId = assignments.optString(folder.getKey(), "");
            List<SavedVideoFolder> target = grouped.get(collectionId);
            if (target != null) {
                target.add(folder);
            }
        }

        List<VideoCollection> result = new ArrayList<>();
        for (CollectionRecord record : records) {
            result.add(new VideoCollection(
                    record.id,
                    record.name,
                    record.createdAtMillis,
                    grouped.get(record.id)
            ));
        }
        return result;
    }

    public static synchronized String create(Context context, String requestedName) {
        String name = VideoFolderUtils.normalizeUserName(requestedName);
        List<CollectionRecord> records = readCollections(context);
        if (name.isEmpty() || containsName(records, name, null)) {
            return null;
        }
        String id = UUID.randomUUID().toString();
        records.add(new CollectionRecord(id, name, System.currentTimeMillis()));
        writeCollections(context, records);
        return id;
    }

    public static synchronized boolean rename(
            Context context,
            String collectionId,
            String requestedName
    ) {
        String name = VideoFolderUtils.normalizeUserName(requestedName);
        List<CollectionRecord> records = readCollections(context);
        if (name.isEmpty() || containsName(records, name, collectionId)) {
            return false;
        }
        boolean changed = false;
        for (int index = 0; index < records.size(); index++) {
            CollectionRecord record = records.get(index);
            if (record.id.equals(collectionId)) {
                records.set(index, new CollectionRecord(
                        record.id,
                        name,
                        record.createdAtMillis
                ));
                changed = true;
                break;
            }
        }
        if (changed) {
            writeCollections(context, records);
        }
        return changed;
    }

    public static synchronized boolean delete(Context context, String collectionId) {
        List<CollectionRecord> records = readCollections(context);
        boolean removed = records.removeIf(record -> record.id.equals(collectionId));
        if (!removed) {
            return false;
        }

        JSONObject assignments = readAssignments(context);
        for (String folderKey : keys(assignments)) {
            if (collectionId.equals(assignments.optString(folderKey, ""))) {
                assignments.remove(folderKey);
            }
        }
        writeCollections(context, records);
        writeAssignments(context, assignments);
        return true;
    }

    public static synchronized boolean assignFolder(
            Context context,
            String folderKey,
            String collectionId
    ) {
        if (folderKey == null || folderKey.isEmpty()) {
            return false;
        }
        JSONObject assignments = readAssignments(context);
        if (collectionId == null || collectionId.isEmpty()) {
            assignments.remove(folderKey);
            writeAssignments(context, assignments);
            return true;
        }
        if (!containsId(readCollections(context), collectionId)) {
            return false;
        }
        try {
            assignments.put(folderKey, collectionId);
        } catch (JSONException ignored) {
            return false;
        }
        writeAssignments(context, assignments);
        return true;
    }

    public static synchronized String collectionIdForFolder(
            Context context,
            String folderKey
    ) {
        return readAssignments(context).optString(folderKey, "");
    }

    public static synchronized void updateFolderKey(
            Context context,
            String oldFolderKey,
            String newFolderKey
    ) {
        JSONObject assignments = readAssignments(context);
        String collectionId = assignments.optString(oldFolderKey, "");
        if (collectionId.isEmpty()) {
            return;
        }
        assignments.remove(oldFolderKey);
        try {
            assignments.put(newFolderKey, collectionId);
        } catch (JSONException ignored) {
            return;
        }
        writeAssignments(context, assignments);
    }

    public static synchronized void reconcile(
            Context context,
            List<SavedVideoFolder> folders
    ) {
        Set<String> validFolderKeys = new HashSet<>();
        for (SavedVideoFolder folder : folders) {
            validFolderKeys.add(folder.getKey());
        }
        Set<String> validCollectionIds = new HashSet<>();
        for (CollectionRecord record : readCollections(context)) {
            validCollectionIds.add(record.id);
        }

        JSONObject assignments = readAssignments(context);
        boolean changed = false;
        for (String folderKey : keys(assignments)) {
            String collectionId = assignments.optString(folderKey, "");
            if (!validFolderKeys.contains(folderKey)
                    || !validCollectionIds.contains(collectionId)) {
                assignments.remove(folderKey);
                changed = true;
            }
        }
        if (changed) {
            writeAssignments(context, assignments);
        }
    }

    private static boolean containsName(
            List<CollectionRecord> records,
            String name,
            String ignoredId
    ) {
        for (CollectionRecord record : records) {
            if (!record.id.equals(ignoredId) && record.name.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsId(List<CollectionRecord> records, String id) {
        for (CollectionRecord record : records) {
            if (record.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static List<CollectionRecord> readCollections(Context context) {
        String serialized = preferences(context).getString(COLLECTIONS_KEY, "[]");
        List<CollectionRecord> result = new ArrayList<>();
        try {
            JSONArray items = new JSONArray(serialized == null ? "[]" : serialized);
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                String id = item.optString("id", "");
                String name = VideoFolderUtils.normalizeUserName(item.optString("name", ""));
                if (!id.isEmpty() && !name.isEmpty()) {
                    result.add(new CollectionRecord(
                            id,
                            name,
                            item.optLong("createdAt", 0L)
                    ));
                }
            }
        } catch (JSONException ignored) {
            // Un classement corrompu est ignoré sans bloquer les vidéos.
        }
        return result;
    }

    private static void writeCollections(Context context, List<CollectionRecord> records) {
        JSONArray items = new JSONArray();
        for (CollectionRecord record : records) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", record.id);
                item.put("name", record.name);
                item.put("createdAt", record.createdAtMillis);
                items.put(item);
            } catch (JSONException ignored) {
                // Les valeurs primitives non nulles sont toujours sérialisables.
            }
        }
        preferences(context).edit().putString(COLLECTIONS_KEY, items.toString()).apply();
    }

    private static JSONObject readAssignments(Context context) {
        String serialized = preferences(context).getString(ASSIGNMENTS_KEY, "{}");
        try {
            return new JSONObject(serialized == null ? "{}" : serialized);
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private static void writeAssignments(Context context, JSONObject assignments) {
        preferences(context).edit()
                .putString(ASSIGNMENTS_KEY, assignments.toString())
                .apply();
    }

    private static List<String> keys(JSONObject object) {
        List<String> result = new ArrayList<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            result.add(iterator.next());
        }
        return result;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
        );
    }

    private static final class CollectionRecord {
        private final String id;
        private final String name;
        private final long createdAtMillis;

        private CollectionRecord(String id, String name, long createdAtMillis) {
            this.id = id;
            this.name = name;
            this.createdAtMillis = createdAtMillis;
        }
    }
}
