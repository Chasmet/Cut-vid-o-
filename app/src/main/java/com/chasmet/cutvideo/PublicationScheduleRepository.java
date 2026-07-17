package com.chasmet.cutvideo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PublicationScheduleRepository {

    private static final String PREFERENCES_NAME = "publication_schedules";
    private static final String ITEMS_KEY = "items";

    private PublicationScheduleRepository() {
    }

    public static synchronized List<PublicationSchedule> listAll(Context context) {
        List<PublicationSchedule> schedules = read(context);
        schedules.sort(Comparator.comparingLong(PublicationSchedule::getScheduledAtMillis));
        return schedules;
    }

    public static synchronized List<PublicationSchedule> listForVideo(
            Context context,
            String videoUri
    ) {
        List<PublicationSchedule> result = new ArrayList<>();
        for (PublicationSchedule schedule : read(context)) {
            if (schedule.getVideoUri().equals(videoUri)) {
                result.add(schedule);
            }
        }
        result.sort(Comparator.comparingLong(PublicationSchedule::getScheduledAtMillis));
        return result;
    }

    public static synchronized PublicationSchedule get(Context context, String id) {
        for (PublicationSchedule schedule : read(context)) {
            if (schedule.getId().equals(id)) {
                return schedule;
            }
        }
        return null;
    }

    public static synchronized int countUpcomingForVideo(Context context, String videoUri) {
        int count = 0;
        for (PublicationSchedule schedule : read(context)) {
            if (schedule.getVideoUri().equals(videoUri) && !schedule.isPublished()) {
                count++;
            }
        }
        return count;
    }

    public static synchronized void save(Context context, PublicationSchedule schedule) {
        List<PublicationSchedule> schedules = read(context);
        boolean replaced = false;
        for (int index = 0; index < schedules.size(); index++) {
            if (schedules.get(index).getId().equals(schedule.getId())) {
                schedules.set(index, schedule);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            schedules.add(schedule);
        }
        write(context, schedules);
    }

    public static synchronized PublicationSchedule setPublished(
            Context context,
            String id,
            boolean published
    ) {
        PublicationSchedule updated = null;
        List<PublicationSchedule> schedules = read(context);
        for (int index = 0; index < schedules.size(); index++) {
            PublicationSchedule schedule = schedules.get(index);
            if (schedule.getId().equals(id)) {
                updated = schedule.withPublished(published);
                schedules.set(index, updated);
                break;
            }
        }
        if (updated != null) {
            write(context, schedules);
        }
        return updated;
    }

    public static synchronized PublicationSchedule delete(Context context, String id) {
        List<PublicationSchedule> schedules = read(context);
        PublicationSchedule removed = null;
        for (int index = schedules.size() - 1; index >= 0; index--) {
            if (schedules.get(index).getId().equals(id)) {
                removed = schedules.remove(index);
                break;
            }
        }
        if (removed != null) {
            write(context, schedules);
        }
        return removed;
    }

    public static synchronized List<PublicationSchedule> deleteForVideos(
            Context context,
            List<SavedVideo> videos
    ) {
        Set<String> videoUris = new HashSet<>();
        for (SavedVideo video : videos) {
            videoUris.add(video.getUri().toString());
        }

        List<PublicationSchedule> schedules = read(context);
        List<PublicationSchedule> removed = new ArrayList<>();
        for (int index = schedules.size() - 1; index >= 0; index--) {
            if (videoUris.contains(schedules.get(index).getVideoUri())) {
                removed.add(schedules.remove(index));
            }
        }
        if (!removed.isEmpty()) {
            write(context, schedules);
        }
        VideoMetadataRepository.deleteForVideos(context, videos);
        return removed;
    }

    public static synchronized void updateVideoName(
            Context context,
            String videoUri,
            String newVideoName
    ) {
        List<PublicationSchedule> schedules = read(context);
        boolean changed = false;
        for (int index = 0; index < schedules.size(); index++) {
            PublicationSchedule schedule = schedules.get(index);
            if (schedule.getVideoUri().equals(videoUri)) {
                schedules.set(index, schedule.withVideoName(newVideoName));
                changed = true;
            }
        }
        if (changed) {
            write(context, schedules);
        }
    }

    private static List<PublicationSchedule> read(Context context) {
        SharedPreferences preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
        );
        String serialized = preferences.getString(ITEMS_KEY, "[]");
        List<PublicationSchedule> result = new ArrayList<>();
        try {
            JSONArray items = new JSONArray(serialized == null ? "[]" : serialized);
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.optJSONObject(index);
                PublicationSchedule schedule = fromJson(item);
                if (schedule != null) {
                    result.add(schedule);
                }
            }
        } catch (JSONException ignored) {
            // Une préférence corrompue ne doit jamais empêcher l'ouverture de la vidéothèque.
        }
        return result;
    }

    private static void write(Context context, List<PublicationSchedule> schedules) {
        JSONArray items = new JSONArray();
        for (PublicationSchedule schedule : schedules) {
            items.put(toJson(schedule));
        }
        context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
        ).edit().putString(ITEMS_KEY, items.toString()).apply();
    }

    private static JSONObject toJson(PublicationSchedule schedule) {
        JSONObject item = new JSONObject();
        try {
            item.put("id", schedule.getId());
            item.put("videoUri", schedule.getVideoUri());
            item.put("videoName", schedule.getVideoName());
            item.put("platform", schedule.getPlatformKey());
            item.put("scheduledAt", schedule.getScheduledAtMillis());
            item.put("title", schedule.getTitle());
            item.put("description", schedule.getDescription());
            item.put("hashtags", schedule.getHashtags());
            item.put("visibility", schedule.getVisibility());
            item.put("createdAt", schedule.getCreatedAtMillis());
            item.put("published", schedule.isPublished());
        } catch (JSONException ignored) {
            // JSONObject n'échoue pas avec ces types primitifs et chaînes non nulles.
        }
        return item;
    }

    private static PublicationSchedule fromJson(JSONObject item) {
        if (item == null) {
            return null;
        }
        String id = item.optString("id", "");
        String videoUri = item.optString("videoUri", "");
        long scheduledAt = item.optLong("scheduledAt", 0L);
        if (id.isEmpty() || videoUri.isEmpty() || scheduledAt <= 0L) {
            return null;
        }
        return new PublicationSchedule(
                id,
                videoUri,
                item.optString("videoName", "Vidéo"),
                item.optString("platform", SocialPlatform.OTHER.getKey()),
                scheduledAt,
                item.optString("title", ""),
                item.optString("description", ""),
                item.optString("hashtags", ""),
                item.optString("visibility", PublicationSchedule.VISIBILITY_PUBLIC),
                item.optLong("createdAt", scheduledAt),
                item.optBoolean("published", false)
        );
    }
}
