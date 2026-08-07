package com.chasmet.cutvideo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Synchronise uniquement le catalogue et les programmations. Les vidéos ne sont jamais envoyées. */
public final class CutVideoLibrarySync {

    private static final String BASE_URL = "https://cut-video-chatgpt-mcp.onrender.com";
    private static final String PREFS = "cut_video_chatgpt_sync";
    private static final String TOKEN_KEY = "device_token";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final int MAX_ATTEMPTS = 4;

    private CutVideoLibrarySync() {
    }

    public static void syncAsync(Context context) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> syncNow(appContext));
    }

    private static void syncNow(Context context) {
        String token = getOrCreateDeviceToken(context);

        // Contacte d'abord Render. Ainsi, même si la construction du catalogue échoue
        // sur un appareil particulier, le serveur reçoit au moins la tentative de connexion.
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                if (!pair(token)) {
                    if (attempt < MAX_ATTEMPTS) sleepBeforeRetry(attempt);
                    continue;
                }

                JSONObject snapshot;
                try {
                    snapshot = buildSnapshot(context);
                } catch (Exception ignored) {
                    return;
                }

                int syncCode = postJson(BASE_URL + "/api/library/sync", snapshot, token);
                if (syncCode >= 200 && syncCode < 300) {
                    return;
                }
            } catch (Exception ignored) {
                // Render gratuit peut être en veille : les tentatives suivantes reprennent automatiquement.
            }
            if (attempt < MAX_ATTEMPTS) sleepBeforeRetry(attempt);
        }
    }

    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(Math.min(15_000L, 3_000L * attempt));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String getOrCreateDeviceToken(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String existing = preferences.getString(TOKEN_KEY, "");
        if (existing != null && !existing.trim().isEmpty()) {
            return existing.trim();
        }
        String created = UUID.randomUUID().toString() + UUID.randomUUID();
        preferences.edit().putString(TOKEN_KEY, created).apply();
        return created;
    }

    private static boolean pair(String token) throws Exception {
        JSONObject body = new JSONObject();
        body.put("device_token", token);
        body.put("app_version", BuildConfig.VERSION_NAME);
        int responseCode = postJson(BASE_URL + "/api/library/pair", body, null);
        return responseCode >= 200 && responseCode < 300;
    }

    private static int postJson(String target, JSONObject body, String bearerToken) throws Exception {
        HttpURLConnection connection = null;
        try {
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection = (HttpURLConnection) new URL(target).openConnection();
            connection.setConnectTimeout(45_000);
            connection.setReadTimeout(60_000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            if (bearerToken != null && !bearerToken.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
                output.flush();
            }
            return connection.getResponseCode();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static JSONObject buildSnapshot(Context context) throws Exception {
        JSONObject root = new JSONObject();
        root.put("synced_at_millis", System.currentTimeMillis());
        root.put("app_version", BuildConfig.VERSION_NAME);

        List<SavedVideoFolder> folders = MediaStoreRepository.loadSavedVideoFolders(context);
        VideoCollectionRepository.reconcile(context, folders);

        Map<String, String> collectionNameByFolderKey = new HashMap<>();
        for (VideoCollection collection : VideoCollectionRepository.list(context, folders)) {
            for (SavedVideoFolder folder : collection.getFolders()) {
                collectionNameByFolderKey.put(folder.getKey(), collection.getName());
            }
        }

        Map<String, List<SavedVideo>> videosByProject = new LinkedHashMap<>();
        Map<String, String> projectByVideoUri = new HashMap<>();

        for (SavedVideoFolder folder : folders) {
            if (VideoFolderUtils.isLegacy(folder.getKey())) {
                for (SavedVideo video : folder.getVideos()) {
                    String projectName = inferProjectName(video.getName());
                    videosByProject.computeIfAbsent(projectName, ignored -> new ArrayList<>()).add(video);
                    projectByVideoUri.put(video.getUri().toString(), projectName);
                }
                continue;
            }

            String folderName = VideoFolderUtils.displayName(folder.getKey()).trim();
            if (folderName.isEmpty()) {
                folderName = "Dossier";
            }
            String collectionName = collectionNameByFolderKey.getOrDefault(folder.getKey(), "").trim();
            String projectPath = collectionName.isEmpty()
                    ? folderName
                    : collectionName + " / " + folderName;

            videosByProject.computeIfAbsent(projectPath, ignored -> new ArrayList<>())
                    .addAll(folder.getVideos());
            for (SavedVideo video : folder.getVideos()) {
                projectByVideoUri.put(video.getUri().toString(), projectPath);
            }
        }

        List<String> projectNames = new ArrayList<>(videosByProject.keySet());
        projectNames.sort(CutVideoLibrarySync::naturalCompare);
        JSONArray projectsJson = new JSONArray();
        for (String projectName : projectNames) {
            JSONObject projectJson = new JSONObject();
            projectJson.put("name", projectName);
            JSONArray videosJson = new JSONArray();

            List<SavedVideo> orderedVideos = new ArrayList<>(videosByProject.get(projectName));
            orderedVideos.sort((left, right) -> naturalCompare(left.getName(), right.getName()));
            for (SavedVideo video : orderedVideos) {
                JSONObject videoJson = new JSONObject();
                videoJson.put("name", video.getName());
                videoJson.put("duration_ms", Math.max(0L, video.getDurationMs()));
                videoJson.put("size_bytes", Math.max(0L, video.getSizeBytes()));
                videoJson.put("date_added_seconds", Math.max(0L, video.getDateAddedSeconds()));
                videosJson.put(videoJson);
            }
            projectJson.put("videos", videosJson);
            projectsJson.put(projectJson);
        }
        root.put("projects", projectsJson);

        JSONArray schedulesJson = new JSONArray();
        for (PublicationSchedule schedule : PublicationScheduleRepository.listAll(context)) {
            JSONObject scheduleJson = new JSONObject();
            scheduleJson.put("id", schedule.getId());
            scheduleJson.put("project", projectByVideoUri.getOrDefault(
                    schedule.getVideoUri(),
                    inferProjectName(schedule.getVideoName())
            ));
            scheduleJson.put("video_name", schedule.getVideoName());
            scheduleJson.put("platform", schedule.getPlatformKey());
            scheduleJson.put("scheduled_at_millis", Math.max(0L, schedule.getScheduledAtMillis()));
            scheduleJson.put("title", schedule.getTitle());
            scheduleJson.put("description", schedule.getDescription());
            scheduleJson.put("hashtags", schedule.getHashtags());
            scheduleJson.put("visibility", schedule.getVisibility());
            scheduleJson.put("account", PublicationAccountRepository.get(context, schedule.getId()));
            scheduleJson.put("published", schedule.isPublished());
            schedulesJson.put(scheduleJson);
        }
        root.put("schedules", schedulesJson);
        return root;
    }

    private static String inferProjectName(String fileName) {
        String name = fileName == null ? "" : fileName.trim().replaceFirst("(?i)\\.mp4$", "");
        name = name.replaceFirst("(?i)[ _-]+(?:part(?:ie)?|clip|video)?[ _-]*\\d+$", "").trim();
        if (name.isEmpty()) {
            return "Autres";
        }
        return name.replace('_', ' ').replaceAll("\\s+", " ").trim();
    }

    private static int naturalCompare(String leftValue, String rightValue) {
        String left = leftValue == null ? "" : leftValue.toLowerCase(Locale.ROOT);
        String right = rightValue == null ? "" : rightValue.toLowerCase(Locale.ROOT);
        int leftIndex = 0;
        int rightIndex = 0;

        while (leftIndex < left.length() && rightIndex < right.length()) {
            char leftChar = left.charAt(leftIndex);
            char rightChar = right.charAt(rightIndex);
            if (Character.isDigit(leftChar) && Character.isDigit(rightChar)) {
                int leftEnd = leftIndex;
                int rightEnd = rightIndex;
                while (leftEnd < left.length() && Character.isDigit(left.charAt(leftEnd))) leftEnd++;
                while (rightEnd < right.length() && Character.isDigit(right.charAt(rightEnd))) rightEnd++;

                String leftNumber = left.substring(leftIndex, leftEnd).replaceFirst("^0+(?!$)", "");
                String rightNumber = right.substring(rightIndex, rightEnd).replaceFirst("^0+(?!$)", "");
                if (leftNumber.length() != rightNumber.length()) {
                    return Integer.compare(leftNumber.length(), rightNumber.length());
                }
                int numberCompare = leftNumber.compareTo(rightNumber);
                if (numberCompare != 0) return numberCompare;
                leftIndex = leftEnd;
                rightIndex = rightEnd;
                continue;
            }
            if (leftChar != rightChar) return Character.compare(leftChar, rightChar);
            leftIndex++;
            rightIndex++;
        }
        return Integer.compare(left.length(), right.length());
    }
}
