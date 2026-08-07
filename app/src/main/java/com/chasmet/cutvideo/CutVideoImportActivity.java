package com.chasmet.cutvideo;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
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

/** Point d'entrée Android utilisé par le compagnon ChatGPT. */
public final class CutVideoImportActivity extends AppCompatActivity {

    private static final String BASE_URL = "https://cut-video-chatgpt-mcp.onrender.com";
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        routeIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        routeIntent(intent);
    }

    @Override
    protected void onDestroy() {
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    private void routeIntent(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null || !"cutvideo".equalsIgnoreCase(data.getScheme())) {
            fail("Lien Cut Vidéo invalide.");
            return;
        }
        if ("import".equalsIgnoreCase(data.getHost())) {
            importSingle(data);
            return;
        }
        if ("import-pack".equalsIgnoreCase(data.getHost())) {
            importPack(data);
            return;
        }
        fail("Lien Cut Vidéo non reconnu.");
    }

    private void importSingle(Uri data) {
        String videoName = value(data, "video_name");
        String account = value(data, "account");
        String platformKey = value(data, "platform");
        String date = value(data, "date");
        String time = value(data, "time");
        String title = value(data, "title");
        String description = value(data, "description");
        String hashtags = value(data, "hashtags");
        String visibility = value(data, "visibility");

        SavedVideo video = findVideo(videoName);
        if (video == null) {
            failAndOpenLibrary("Vidéo introuvable dans Cut Vidéo : " + videoName);
            return;
        }
        PublicationSchedule schedule = createSchedule(
                UUID.randomUUID().toString(),
                video,
                account,
                platformKey,
                date,
                time,
                title,
                description,
                hashtags,
                visibility
        );
        if (schedule == null) return;

        saveSchedule(schedule, account);
        CutVideoLibrarySync.syncAsync(this);
        Toast.makeText(this, "Programmation importée • " + schedule.getPlatform().getDisplayName(), Toast.LENGTH_LONG).show();
        Intent open = VideoScheduleActivity.createIntent(this, video);
        open.putExtra(VideoScheduleActivity.EXTRA_FOCUS_SCHEDULE_ID, schedule.getId());
        startActivity(open);
        finish();
    }

    private void importPack(Uri data) {
        String packId = value(data, "id");
        if (packId.isEmpty()) {
            fail("Lot ChatGPT incomplet.");
            return;
        }
        Toast.makeText(this, "Import du lot ChatGPT…", Toast.LENGTH_SHORT).show();
        networkExecutor.execute(() -> {
            try {
                JSONObject pack = fetchPack(packId);
                runOnUiThread(() -> applyPack(packId, pack));
            } catch (Exception error) {
                runOnUiThread(() -> fail("Impossible de récupérer le lot ChatGPT."));
            }
        });
    }

    private JSONObject fetchPack(String packId) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(BASE_URL + "/api/import-pack/" + Uri.encode(packId));
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }
            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder text = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) text.append(line);
                return new JSONObject(text.toString());
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void applyPack(String packId, JSONObject pack) {
        JSONArray publications = pack.optJSONArray("publications");
        if (publications == null || publications.length() == 0) {
            fail("Le lot ChatGPT est vide.");
            return;
        }

        int imported = 0;
        int missing = 0;
        for (int index = 0; index < publications.length(); index++) {
            JSONObject item = publications.optJSONObject(index);
            if (item == null) continue;
            String videoName = item.optString("video_name", "").trim();
            SavedVideo video = findVideo(videoName);
            if (video == null) {
                missing++;
                continue;
            }
            String hashtags = hashtagsText(item.optJSONArray("hashtags"));
            String idSeed = "chatgpt-pack:" + packId + ":" + videoName + ":"
                    + item.optString("platform", "") + ":"
                    + item.optString("date", "") + ":" + item.optString("time", "");
            String scheduleId = UUID.nameUUIDFromBytes(idSeed.getBytes(StandardCharsets.UTF_8)).toString();
            PublicationSchedule schedule = createSchedule(
                    scheduleId,
                    video,
                    item.optString("account", ""),
                    item.optString("platform", ""),
                    item.optString("date", ""),
                    item.optString("time", ""),
                    item.optString("title", ""),
                    item.optString("description", ""),
                    hashtags,
                    item.optString("visibility", "")
            );
            if (schedule != null) {
                saveSchedule(schedule, item.optString("account", ""));
                imported++;
            }
        }

        CutVideoLibrarySync.syncAsync(this);
        String message = imported + " programmation" + (imported > 1 ? "s" : "") + " importée" + (imported > 1 ? "s" : "");
        if (missing > 0) message += " • " + missing + " vidéo(s) introuvable(s)";
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        startActivity(new Intent(this, SavedVideosActivity.class));
        finish();
    }

    private PublicationSchedule createSchedule(
            String scheduleId,
            SavedVideo video,
            String account,
            String platformKey,
            String date,
            String time,
            String title,
            String description,
            String hashtags,
            String visibility
    ) {
        if (video == null || platformKey == null || date == null || time == null) {
            fail("La fiche ChatGPT est incomplète.");
            return null;
        }
        long scheduledAt = parseDateTime(date, time);
        if (scheduledAt <= 0L) {
            fail("Date ou heure de programmation invalide.");
            return null;
        }
        SocialPlatform platform = SocialPlatform.fromKey(platformKey);
        if (platform == SocialPlatform.OTHER) {
            fail("Réseau non reconnu : " + platformKey);
            return null;
        }
        return new PublicationSchedule(
                scheduleId,
                video.getUri().toString(),
                video.getName(),
                platform.getKey(),
                scheduledAt,
                title,
                description,
                hashtags,
                visibility == null || visibility.trim().isEmpty()
                        ? PublicationSchedule.VISIBILITY_PUBLIC
                        : visibility,
                System.currentTimeMillis(),
                false
        );
    }

    private void saveSchedule(PublicationSchedule schedule, String account) {
        PublicationScheduleRepository.save(this, schedule);
        PublicationAccountRepository.save(this, schedule.getId(), account);
        PublicationReminderReceiver.ensureNotificationChannel(this);
        PublicationReminderScheduler.schedule(this, schedule);
    }

    private SavedVideo findVideo(String requestedName) {
        List<SavedVideo> videos = MediaStoreRepository.loadSavedVideos(this);
        for (SavedVideo video : videos) {
            if (video.getName().equalsIgnoreCase(requestedName)) return video;
        }
        String requestedBase = withoutExtension(requestedName);
        for (SavedVideo video : videos) {
            if (withoutExtension(video.getName()).equalsIgnoreCase(requestedBase)) return video;
        }
        return null;
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

    private static String value(Uri uri, String key) {
        String value = uri.getQueryParameter(key);
        return value == null ? "" : value.trim();
    }

    private void fail(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    private void failAndOpenLibrary(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        startActivity(new Intent(this, SavedVideosActivity.class));
        finish();
    }
}
