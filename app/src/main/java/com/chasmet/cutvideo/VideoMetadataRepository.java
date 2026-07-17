package com.chasmet.cutvideo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

public final class VideoMetadataRepository {

    private static final String PREFERENCES_NAME = "permanent_video_metadata";

    private VideoMetadataRepository() {
    }

    public static synchronized VideoMetadata get(Context context, String videoUri) {
        if (videoUri == null || videoUri.trim().isEmpty()) {
            return null;
        }
        String serialized = preferences(context).getString(videoUri, null);
        if (serialized == null || serialized.isEmpty()) {
            return null;
        }
        try {
            JSONObject item = new JSONObject(serialized);
            VideoMetadata metadata = new VideoMetadata(
                    item.optString("title", ""),
                    item.optString("description", ""),
                    item.optString("hashtags", "")
            );
            return metadata.isEmpty() ? null : metadata;
        } catch (JSONException ignored) {
            return null;
        }
    }

    public static synchronized void save(
            Context context,
            String videoUri,
            VideoMetadata metadata
    ) {
        if (videoUri == null
                || videoUri.trim().isEmpty()
                || metadata == null
                || metadata.isEmpty()) {
            return;
        }
        JSONObject item = new JSONObject();
        try {
            item.put("title", metadata.getTitle());
            item.put("description", metadata.getDescription());
            item.put("hashtags", metadata.getHashtags());
        } catch (JSONException ignored) {
            return;
        }
        preferences(context).edit().putString(videoUri, item.toString()).apply();
    }

    public static synchronized void deleteForVideos(
            Context context,
            List<SavedVideo> videos
    ) {
        if (videos == null || videos.isEmpty()) {
            return;
        }
        SharedPreferences.Editor editor = preferences(context).edit();
        for (SavedVideo video : videos) {
            editor.remove(video.getUri().toString());
        }
        editor.apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
        );
    }
}
