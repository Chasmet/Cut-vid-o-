package com.chasmet.cutvideo;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Extrait quelques images représentatives de chaque vidéo et les synchronise avec le compagnon
 * ChatGPT. Les fichiers vidéo eux-mêmes ne quittent jamais l'appareil.
 */
public final class VideoFrameSync {

    private static final String BASE_URL = "https://cut-video-chatgpt-mcp.onrender.com";
    private static final String PREFS = "cut_video_chatgpt_frame_cache";
    private static final int FRAME_COUNT = 3;
    private static final int MAX_EDGE_PX = 480;
    private static final int JPEG_QUALITY = 52;
    private static final long CACHE_MAX_AGE_MS = 6L * 24L * 60L * 60L * 1000L;

    private VideoFrameSync() {
    }

    /**
     * Retourne uniquement les URLs déjà en cache. Cette méthode ne lit aucune image de la vidéo
     * et ne fait aucun appel réseau : elle peut donc être utilisée pendant la synchro rapide.
     */
    public static List<String> getCachedFrameUrls(
            Context context,
            String projectName,
            SavedVideo video
    ) {
        if (context == null || video == null) return Collections.emptyList();
        String signature = signature(projectName, video);
        String cacheKey = "frames_" + sha256(signature);
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        CachedFrames cached = readCache(preferences, cacheKey, signature);
        if (cached == null || cached.urls.isEmpty()) return Collections.emptyList();
        if (System.currentTimeMillis() - cached.savedAtMillis >= CACHE_MAX_AGE_MS) {
            return Collections.emptyList();
        }
        return new ArrayList<>(cached.urls);
    }

    public static List<String> ensureFramesUploaded(
            Context context,
            String bearerToken,
            String projectName,
            SavedVideo video
    ) {
        if (context == null || video == null || bearerToken == null || bearerToken.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String signature = signature(projectName, video);
        String cacheKey = "frames_" + sha256(signature);
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        CachedFrames cached = readCache(preferences, cacheKey, signature);
        long now = System.currentTimeMillis();
        if (cached != null && !cached.urls.isEmpty() && now - cached.savedAtMillis < CACHE_MAX_AGE_MS) {
            return cached.urls;
        }

        try {
            List<String> frameBase64 = extractFrames(context, video);
            if (frameBase64.isEmpty()) {
                return cached == null ? Collections.emptyList() : cached.urls;
            }
            List<String> urls = uploadFrames(
                    bearerToken.trim(),
                    projectName == null ? "" : projectName.trim(),
                    video.getName(),
                    signature,
                    frameBase64
            );
            if (!urls.isEmpty()) {
                writeCache(preferences, cacheKey, signature, urls, now);
                return urls;
            }
        } catch (Exception ignored) {
            // L'analyse visuelle est secondaire : elle ne doit jamais bloquer la bibliothèque.
        }
        return cached == null ? Collections.emptyList() : cached.urls;
    }

    private static List<String> extractFrames(Context context, SavedVideo video) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        List<String> result = new ArrayList<>();
        try {
            retriever.setDataSource(context, video.getUri());
            long durationMs = Math.max(1L, video.getDurationMs());
            double[] positions = {0.15d, 0.50d, 0.85d};
            for (int index = 0; index < Math.min(FRAME_COUNT, positions.length); index++) {
                long timeUs = Math.max(0L, (long) (durationMs * positions[index] * 1000L));
                Bitmap bitmap = retriever.getFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                );
                if (bitmap == null) {
                    bitmap = retriever.getFrameAtTime(
                            timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST
                    );
                }
                if (bitmap == null) continue;

                Bitmap scaled = scaleDown(bitmap, MAX_EDGE_PX);
                if (scaled != bitmap) bitmap.recycle();
                try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    if (scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                        result.add(Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
                    }
                } catch (Exception ignored) {
                    // Une frame défectueuse ne doit pas empêcher les autres d'être envoyées.
                } finally {
                    scaled.recycle();
                }
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Rien à faire.
            }
        }
        return result;
    }

    private static Bitmap scaleDown(Bitmap source, int maxEdgePx) {
        int width = Math.max(1, source.getWidth());
        int height = Math.max(1, source.getHeight());
        int largest = Math.max(width, height);
        if (largest <= maxEdgePx) return source;
        float ratio = maxEdgePx / (float) largest;
        int targetWidth = Math.max(1, Math.round(width * ratio));
        int targetHeight = Math.max(1, Math.round(height * ratio));
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
    }

    private static List<String> uploadFrames(
            String bearerToken,
            String projectName,
            String videoName,
            String signature,
            List<String> frames
    ) throws Exception {
        JSONObject body = new JSONObject();
        body.put("project", projectName);
        body.put("video_name", videoName == null ? "" : videoName.trim());
        body.put("signature", signature);
        JSONArray frameArray = new JSONArray();
        for (int index = 0; index < Math.min(FRAME_COUNT, frames.size()); index++) {
            JSONObject frame = new JSONObject();
            frame.put("index", index);
            frame.put("mime_type", "image/jpeg");
            frame.put("data_base64", frames.get(index));
            frameArray.put(frame);
        }
        body.put("frames", frameArray);

        HttpURLConnection connection = null;
        try {
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection = (HttpURLConnection) new URL(BASE_URL + "/api/library/frames").openConnection();
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(60_000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
                output.flush();
            }

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Frame sync HTTP " + code);
            }
            String response = readText(connection.getInputStream());
            JSONObject json = new JSONObject(response);
            JSONArray urlsJson = json.optJSONArray("frame_urls");
            if (urlsJson == null) return Collections.emptyList();
            List<String> urls = new ArrayList<>();
            for (int index = 0; index < urlsJson.length(); index++) {
                String value = urlsJson.optString(index, "").trim();
                if (!value.isEmpty()) urls.add(value);
            }
            return urls;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readText(InputStream input) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8)
        )) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) text.append(line);
            return text.toString();
        }
    }

    private static CachedFrames readCache(
            SharedPreferences preferences,
            String cacheKey,
            String expectedSignature
    ) {
        String raw = preferences.getString(cacheKey, "");
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JSONObject json = new JSONObject(raw);
            if (!expectedSignature.equals(json.optString("signature", ""))) return null;
            JSONArray urlsJson = json.optJSONArray("urls");
            if (urlsJson == null) return null;
            List<String> urls = new ArrayList<>();
            for (int index = 0; index < urlsJson.length(); index++) {
                String value = urlsJson.optString(index, "").trim();
                if (!value.isEmpty()) urls.add(value);
            }
            return new CachedFrames(urls, json.optLong("saved_at_millis", 0L));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeCache(
            SharedPreferences preferences,
            String cacheKey,
            String signature,
            List<String> urls,
            long savedAtMillis
    ) {
        try {
            JSONObject json = new JSONObject();
            json.put("signature", signature);
            json.put("saved_at_millis", savedAtMillis);
            JSONArray array = new JSONArray();
            for (String url : urls) array.put(url);
            json.put("urls", array);
            preferences.edit().putString(cacheKey, json.toString()).apply();
        } catch (Exception ignored) {
            // Cache facultatif.
        }
    }

    private static String signature(String projectName, SavedVideo video) {
        return String.format(
                Locale.ROOT,
                "%s|%s|%d|%d|%d",
                projectName == null ? "" : projectName,
                video.getName(),
                Math.max(0L, video.getDurationMs()),
                Math.max(0L, video.getSizeBytes()),
                Math.max(0L, video.getDateAddedSeconds())
        );
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) result.append(String.format(Locale.ROOT, "%02x", item));
            return result.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static final class CachedFrames {
        final List<String> urls;
        final long savedAtMillis;

        CachedFrames(List<String> urls, long savedAtMillis) {
            this.urls = urls;
            this.savedAtMillis = Math.max(0L, savedAtMillis);
        }
    }
}
