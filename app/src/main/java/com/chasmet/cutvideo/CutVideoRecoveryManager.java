package com.chasmet.cutvideo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Restaure les programmations synchronisées avant une réinstallation exceptionnelle.
 * La restauration n'est tentée que lorsque le stockage local des programmations est vide.
 */
public final class CutVideoRecoveryManager {

    private static final String BASE_URL = "https://cut-video-chatgpt-mcp.onrender.com";
    private static final String PREFS = "cut_video_recovery";
    private static final String KEY_DONE = "recovery_done";

    private CutVideoRecoveryManager() {
    }

    public static int restoreIfNeeded(Context context, String token) {
        Context appContext = context.getApplicationContext();
        SharedPreferences preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        if (preferences.getBoolean(KEY_DONE, false)) {
            return 0;
        }
        if (!PublicationScheduleRepository.listAll(appContext).isEmpty()) {
            preferences.edit().putBoolean(KEY_DONE, true).apply();
            return 0;
        }
        if (token == null || token.trim().isEmpty()) {
            return 0;
        }

        try {
            JSONObject snapshot = getJson(BASE_URL + "/api/library/recovery", token.trim());
            JSONArray remoteSchedules = snapshot.optJSONArray("schedules");
            if (remoteSchedules == null || remoteSchedules.length() == 0) {
                preferences.edit().putBoolean(KEY_DONE, true).apply();
                return 0;
            }

            Map<String, SavedVideo> videos = indexVideos(appContext);
            int restored = 0;
            for (int index = 0; index < remoteSchedules.length(); index++) {
                JSONObject item = remoteSchedules.optJSONObject(index);
                if (item == null) continue;

                String remoteName = item.optString("video_name", "").trim();
                SavedVideo video = videos.get(normalize(remoteName));
                if (video == null) {
                    video = videos.get(normalize(withoutExtension(remoteName)));
                }
                if (video == null) continue;

                String id = item.optString("id", "").trim();
                long scheduledAt = item.optLong("scheduled_at_millis", 0L);
                String platform = item.optString("platform", "").trim();
                if (id.isEmpty()
                        || scheduledAt <= 0L
                        || SocialPlatform.fromKey(platform) == SocialPlatform.OTHER) {
                    continue;
                }

                PublicationSchedule schedule = new PublicationSchedule(
                        id,
                        video.getUri().toString(),
                        video.getName(),
                        platform,
                        scheduledAt,
                        item.optString("title", ""),
                        item.optString("description", ""),
                        item.optString("hashtags", ""),
                        item.optString("visibility", PublicationSchedule.VISIBILITY_PUBLIC),
                        scheduledAt,
                        item.optBoolean("published", false)
                );
                PublicationScheduleRepository.save(appContext, schedule);
                PublicationAccountRepository.save(
                        appContext,
                        id,
                        item.optString("account", "")
                );

                VideoMetadata metadata = new VideoMetadata(
                        schedule.getTitle(),
                        schedule.getDescription(),
                        schedule.getHashtags()
                );
                if (!metadata.isEmpty()) {
                    VideoMetadataRepository.save(
                            appContext,
                            video.getUri().toString(),
                            metadata
                    );
                }
                PublicationReminderScheduler.schedule(appContext, schedule);
                restored++;
            }

            if (restored > 0) {
                preferences.edit().putBoolean(KEY_DONE, true).apply();
            }
            return restored;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static Map<String, SavedVideo> indexVideos(Context context) {
        List<SavedVideo> localVideos = MediaStoreRepository.loadSavedVideos(context);
        Map<String, SavedVideo> result = new HashMap<>();
        for (SavedVideo video : localVideos) {
            result.put(normalize(video.getName()), video);
            result.put(normalize(withoutExtension(video.getName())), video);
        }
        return result;
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String withoutExtension(String value) {
        String safe = value == null ? "" : value.trim();
        int dot = safe.lastIndexOf('.');
        return dot > 0 ? safe.substring(0, dot) : safe;
    }

    private static JSONObject getJson(String target, String token) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(target).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }
            return readJson(connection.getInputStream());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static JSONObject readJson(InputStream input) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) text.append(line);
            return new JSONObject(text.toString());
        }
    }
}
