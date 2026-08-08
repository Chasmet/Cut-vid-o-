package com.chasmet.cutvideo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Récupère les commandes de programmation créées par ChatGPT et les écrit réellement
 * dans le stockage local Cut Vidéo. Une commande n'est confirmée au serveur qu'après
 * enregistrement local + planification du rappel Android.
 */
public final class CutVideoRemoteScheduleSync {

    private static final String BASE_URL = "https://cut-video-chatgpt-mcp.onrender.com";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private CutVideoRemoteScheduleSync() {
    }

    public static void pullAsync(Context context, String token) {
        Context appContext = context.getApplicationContext();
        if (token == null || token.trim().isEmpty()) return;
        EXECUTOR.execute(() -> pullNow(appContext, token.trim()));
    }

    private static void pullNow(Context context, String token) {
        try {
            JSONObject payload = getJson(BASE_URL + "/api/device/commands", token);
            JSONArray commands = payload.optJSONArray("commands");
            if (commands == null || commands.length() == 0) return;

            int totalApplied = 0;
            int totalProblems = 0;
            for (int index = 0; index < commands.length(); index++) {
                JSONObject command = commands.optJSONObject(index);
                if (command == null) continue;
                CommandResult result = applyCommand(context, command);
                totalApplied += result.applied;
                totalProblems += result.missing + result.invalid;
                acknowledge(token, command.optString("id", ""), result);
            }

            if (totalApplied > 0) {
                PublicationReminderReceiver.ensureNotificationChannel(context);
                showStatus(
                        context,
                        "ChatGPT → " + totalApplied + " programmation"
                                + (totalApplied > 1 ? "s" : "") + " enregistrée"
                                + (totalApplied > 1 ? "s" : "")
                                + (totalProblems > 0 ? " • " + totalProblems + " erreur(s)" : "")
                );
                // Renvoyer aussitôt la bibliothèque permet à ChatGPT de constater les vraies écritures.
                CutVideoLibrarySync.syncAsync(context);
            }
        } catch (Exception ignored) {
            // La programmation distante ne doit jamais empêcher l'utilisation locale de l'application.
        }
    }

    private static CommandResult applyCommand(Context context, JSONObject command) {
        JSONArray publications = command.optJSONArray("publications");
        if (publications == null) return new CommandResult(0, 0, 1);

        int applied = 0;
        int missing = 0;
        int invalid = 0;
        String commandId = command.optString("id", "").trim();

        for (int index = 0; index < publications.length(); index++) {
            JSONObject item = publications.optJSONObject(index);
            if (item == null) {
                invalid++;
                continue;
            }
            String videoName = item.optString("video_name", "").trim();
            SavedVideo video = findVideo(context, videoName);
            if (video == null) {
                missing++;
                continue;
            }

            String platformKey = item.optString("platform", "").trim();
            SocialPlatform platform = SocialPlatform.fromKey(platformKey);
            long scheduledAt = parseDateTime(
                    item.optString("date", "").trim(),
                    item.optString("time", "").trim()
            );
            if (platform == SocialPlatform.OTHER || scheduledAt <= 0L) {
                invalid++;
                continue;
            }

            String idSeed = "chatgpt-remote:" + commandId + ":" + videoName + ":"
                    + platformKey + ":" + item.optString("date", "") + ":" + item.optString("time", "");
            String scheduleId = UUID.nameUUIDFromBytes(idSeed.getBytes(StandardCharsets.UTF_8)).toString();

            PublicationSchedule schedule = new PublicationSchedule(
                    scheduleId,
                    video.getUri().toString(),
                    video.getName(),
                    platform.getKey(),
                    scheduledAt,
                    item.optString("title", ""),
                    item.optString("description", ""),
                    hashtagsText(item.optJSONArray("hashtags")),
                    normalizedVisibility(item.optString("visibility", "")),
                    System.currentTimeMillis(),
                    false
            );

            PublicationScheduleRepository.save(context, schedule);
            PublicationAccountRepository.save(context, scheduleId, item.optString("account", ""));
            PublicationReminderScheduler.schedule(context, schedule);
            applied++;
        }

        return new CommandResult(applied, missing, invalid);
    }

    private static SavedVideo findVideo(Context context, String requestedName) {
        List<SavedVideo> videos = MediaStoreRepository.loadSavedVideos(context);
        for (SavedVideo video : videos) {
            if (video.getName().equalsIgnoreCase(requestedName)) return video;
        }
        String requestedBase = withoutExtension(requestedName);
        for (SavedVideo video : videos) {
            if (withoutExtension(video.getName()).equalsIgnoreCase(requestedBase)) return video;
        }
        return null;
    }

    private static String normalizedVisibility(String value) {
        String safe = value == null ? "" : value.trim();
        return safe.isEmpty() ? PublicationSchedule.VISIBILITY_PUBLIC : safe;
    }

    private static String hashtagsText(JSONArray values) {
        if (values == null) return "";
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.length(); index++) {
            String value = values.optString(index, "").trim();
            if (value.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(value);
        }
        return result.toString();
    }

    private static String withoutExtension(String name) {
        String safe = name == null ? "" : name.trim();
        int dot = safe.lastIndexOf('.');
        return dot > 0 ? safe.substring(0, dot) : safe;
    }

    private static long parseDateTime(String date, String time) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.FRANCE);
        format.setLenient(false);
        try {
            return format.parse(date + " " + time).getTime();
        } catch (ParseException | NullPointerException ignored) {
            return -1L;
        }
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
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            return readJson(connection.getInputStream());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void acknowledge(String token, String commandId, CommandResult result) {
        if (commandId == null || commandId.trim().isEmpty()) return;
        HttpURLConnection connection = null;
        try {
            JSONObject body = new JSONObject();
            String status = result.applied == 0
                    ? "failed"
                    : (result.missing == 0 && result.invalid == 0 ? "applied" : "partial");
            body.put("status", status);
            body.put("applied", result.applied);
            body.put("missing", result.missing);
            body.put("invalid", result.invalid);

            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            URL url = new URL(BASE_URL + "/api/device/commands/" + commandId + "/ack");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(data.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(data);
                output.flush();
            }
            connection.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static JSONObject readJson(InputStream input) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) text.append(line);
            return new JSONObject(text.toString());
        }
    }

    private static void showStatus(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        );
    }

    private static final class CommandResult {
        final int applied;
        final int missing;
        final int invalid;

        CommandResult(int applied, int missing, int invalid) {
            this.applied = applied;
            this.missing = missing;
            this.invalid = invalid;
        }
    }
}
