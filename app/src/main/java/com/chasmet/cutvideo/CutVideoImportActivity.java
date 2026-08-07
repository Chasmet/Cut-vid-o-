package com.chasmet.cutvideo;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Point d'entrée Android utilisé par le compagnon ChatGPT.
 * Reçoit cutvideo://import?... puis crée localement la programmation correspondante.
 */
public final class CutVideoImportActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        importFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        importFromIntent(intent);
    }

    private void importFromIntent(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null
                || !"cutvideo".equalsIgnoreCase(data.getScheme())
                || !"import".equalsIgnoreCase(data.getHost())) {
            Toast.makeText(this, "Lien Cut Vidéo invalide.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        String videoName = value(data, "video_name");
        String account = value(data, "account");
        String platformKey = value(data, "platform");
        String date = value(data, "date");
        String time = value(data, "time");
        String title = value(data, "title");
        String description = value(data, "description");
        String hashtags = value(data, "hashtags");
        String visibility = value(data, "visibility");

        if (videoName.isEmpty() || platformKey.isEmpty() || date.isEmpty() || time.isEmpty()) {
            Toast.makeText(this, "La fiche ChatGPT est incomplète.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        SavedVideo video = findVideo(videoName);
        if (video == null) {
            Toast.makeText(
                    this,
                    "Vidéo introuvable dans Cut Vidéo : " + videoName,
                    Toast.LENGTH_LONG
            ).show();
            startActivity(new Intent(this, SavedVideosActivity.class));
            finish();
            return;
        }

        long scheduledAt = parseDateTime(date, time);
        if (scheduledAt <= 0L) {
            Toast.makeText(this, "Date ou heure de programmation invalide.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        SocialPlatform platform = SocialPlatform.fromKey(platformKey);
        if (platform == SocialPlatform.OTHER) {
            Toast.makeText(this, "Réseau non reconnu : " + platformKey, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        String scheduleId = UUID.randomUUID().toString();
        PublicationSchedule schedule = new PublicationSchedule(
                scheduleId,
                video.getUri().toString(),
                video.getName(),
                platform.getKey(),
                scheduledAt,
                title,
                description,
                hashtags,
                visibility.isEmpty() ? PublicationSchedule.VISIBILITY_PUBLIC : visibility,
                System.currentTimeMillis(),
                false
        );

        PublicationScheduleRepository.save(this, schedule);
        PublicationAccountRepository.save(this, scheduleId, account);
        PublicationReminderReceiver.ensureNotificationChannel(this);
        PublicationReminderScheduler.schedule(this, schedule);

        String accountLabel = PublicationAccountRepository.get(this, scheduleId);
        Toast.makeText(
                this,
                "Programmation importée • " + platform.getDisplayName()
                        + (accountLabel.isEmpty() ? "" : " • " + accountLabel),
                Toast.LENGTH_LONG
        ).show();

        Intent open = VideoScheduleActivity.createIntent(this, video);
        open.putExtra(VideoScheduleActivity.EXTRA_FOCUS_SCHEDULE_ID, scheduleId);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(open);
        finish();
    }

    private SavedVideo findVideo(String requestedName) {
        List<SavedVideo> videos = MediaStoreRepository.loadSavedVideos(this);
        for (SavedVideo video : videos) {
            if (video.getName().equalsIgnoreCase(requestedName)) {
                return video;
            }
        }

        String requestedBase = withoutExtension(requestedName);
        for (SavedVideo video : videos) {
            if (withoutExtension(video.getName()).equalsIgnoreCase(requestedBase)) {
                return video;
            }
        }
        return null;
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
}
