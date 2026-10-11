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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Canal d'administration MCP pour Cut Vidéo.
 * Les actions autorisées ici modifient réellement l'état local de l'application puis accusent
 * réception au serveur. Les protections imposées par Android restent prioritaires.
 */
public final class CutVideoRemoteAdminSync {

    private static final String BASE_URL = "https://cut-video-chatgpt-mcp.onrender.com";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private CutVideoRemoteAdminSync() {
    }

    public static void pullAsync(Context context, String token) {
        Context appContext = context.getApplicationContext();
        if (token == null || token.trim().isEmpty()) return;
        EXECUTOR.execute(() -> pullNow(appContext, token.trim()));
    }

    private static void pullNow(Context context, String token) {
        try {
            JSONObject payload = getJson(BASE_URL + "/api/device/admin-commands", token);
            JSONArray commands = payload.optJSONArray("commands");
            if (commands == null || commands.length() == 0) return;

            int applied = 0;
            int failed = 0;
            for (int index = 0; index < commands.length(); index++) {
                JSONObject command = commands.optJSONObject(index);
                if (command == null) continue;

                CommandResult result = applyCommand(context, command);
                if (result.applied) applied++;
                else failed++;
                acknowledge(token, command.optString("id", ""), result);
            }

            if (applied > 0) {
                showStatus(
                        context,
                        "ChatGPT → " + applied + " action"
                                + (applied > 1 ? "s" : "") + " appliquée"
                                + (applied > 1 ? "s" : "")
                                + (failed > 0 ? " • " + failed + " échec(s)" : "")
                );
                CutVideoLibrarySync.syncAsync(context);
            }
        } catch (Exception ignored) {
            // Le contrôle distant ne doit jamais bloquer l'usage local.
        }
    }

    private static CommandResult applyCommand(Context context, JSONObject command) {
        String action = command.optString("action", "").trim().toLowerCase(Locale.ROOT);
        JSONObject payload = command.optJSONObject("payload");
        if (payload == null) payload = new JSONObject();

        try {
            switch (action) {
                case "ping":
                    return CommandResult.ok("APK Cut Vidéo joignable — v" + BuildConfig.VERSION_NAME);

                case "sync_now":
                    CutVideoLibrarySync.syncAsync(context);
                    return CommandResult.ok("Synchronisation Cut Vidéo relancée.");

                case "reschedule_all":
                    PublicationReminderScheduler.rescheduleAll(context);
                    return CommandResult.ok("Tous les rappels ont été reprogrammés.");

                case "set_video_metadata":
                    return setVideoMetadata(context, payload);

                case "create_schedule":
                    return createSchedule(context, payload);

                case "update_schedule":
                    return updateSchedule(context, payload);

                case "create_collection":
                    return createCollection(context, payload);

                case "rename_collection":
                    return renameCollection(context, payload);

                case "delete_collection":
                    return deleteCollection(context, payload);

                case "assign_folder":
                    return assignFolder(context, payload);

                case "rename_folder":
                    return renameFolder(context, payload);

                case "set_folder_note":
                    return setFolderNote(context, payload);

                case "set_collection_note":
                    return setCollectionNote(context, payload);

                case "rename_video":
                    return renameVideo(context, payload);

                case "delete_video":
                    return deleteVideo(context, payload);

                case "delete_schedule":
                    return deleteSchedule(context, payload);

                case "mark_schedule":
                    return markSchedule(context, payload);

                case "request_update":
                    AppUpdateManager.requestUpdateFromMcp(context);
                    return CommandResult.ok(
                            "Mise à jour demandée. Cut Vidéo ouvrira la vérification au prochain retour au premier plan."
                    );

                default:
                    return CommandResult.fail("Action MCP inconnue : " + action);
            }
        } catch (Exception error) {
            String message = error.getMessage();
            return CommandResult.fail(
                    message == null || message.trim().isEmpty()
                            ? error.getClass().getSimpleName()
                            : message.trim()
            );
        }
    }

    private static CommandResult setVideoMetadata(Context context, JSONObject payload) {
        SavedVideo video = findVideo(context, text(payload, "video_name"));
        if (video == null) return CommandResult.fail("Vidéo introuvable.");

        VideoMetadata metadata = new VideoMetadata(
                text(payload, "title"),
                text(payload, "description"),
                text(payload, "hashtags")
        );
        if (metadata.isEmpty()) return CommandResult.fail("Métadonnées vides.");

        VideoMetadataRepository.save(context, video.getUri().toString(), metadata);
        return CommandResult.ok("Métadonnées enregistrées pour " + video.getName() + ".");
    }

    private static CommandResult createSchedule(Context context, JSONObject payload) {
        SavedVideo video = findVideo(context, text(payload, "video_name"));
        if (video == null) return CommandResult.fail("Vidéo introuvable.");

        String platform = text(payload, "platform");
        SocialPlatform socialPlatform = SocialPlatform.fromKey(platform);
        if (socialPlatform == SocialPlatform.OTHER) {
            return CommandResult.fail("Réseau social invalide.");
        }

        long scheduledAt = scheduleTime(payload);
        if (scheduledAt <= 0L) return CommandResult.fail("Date/heure de programmation invalide.");

        String id = text(payload, "schedule_id");
        if (id.isEmpty()) id = UUID.randomUUID().toString();

        PublicationSchedule schedule = new PublicationSchedule(
                id,
                video.getUri().toString(),
                video.getName(),
                socialPlatform.getKey(),
                scheduledAt,
                text(payload, "title"),
                text(payload, "description"),
                text(payload, "hashtags"),
                visibility(payload),
                System.currentTimeMillis(),
                payload.optBoolean("published", false)
        );
        PublicationScheduleRepository.save(context, schedule);
        PublicationAccountRepository.save(context, id, text(payload, "account"));
        PublicationReminderScheduler.schedule(context, schedule);
        return CommandResult.ok("Programmation créée : " + id);
    }

    private static CommandResult updateSchedule(Context context, JSONObject payload) {
        String id = text(payload, "schedule_id");
        PublicationSchedule current = PublicationScheduleRepository.get(context, id);
        if (current == null) return CommandResult.fail("Programmation introuvable.");

        String platformText = text(payload, "platform");
        String platform = platformText.isEmpty() ? current.getPlatformKey() : platformText;
        if (SocialPlatform.fromKey(platform) == SocialPlatform.OTHER) {
            return CommandResult.fail("Réseau social invalide.");
        }

        long scheduledAt = payload.has("scheduled_at_millis")
                || !text(payload, "date").isEmpty()
                || !text(payload, "time").isEmpty()
                ? scheduleTime(payload)
                : current.getScheduledAtMillis();
        if (scheduledAt <= 0L) return CommandResult.fail("Date/heure de programmation invalide.");

        PublicationSchedule updated = new PublicationSchedule(
                current.getId(),
                current.getVideoUri(),
                current.getVideoName(),
                platform,
                scheduledAt,
                valueOr(payload, "title", current.getTitle()),
                valueOr(payload, "description", current.getDescription()),
                valueOr(payload, "hashtags", current.getHashtags()),
                payload.has("visibility") ? visibility(payload) : current.getVisibility(),
                current.getCreatedAtMillis(),
                payload.has("published") ? payload.optBoolean("published") : current.isPublished()
        );

        PublicationReminderScheduler.cancel(context, current);
        PublicationScheduleRepository.save(context, updated);
        if (payload.has("account")) {
            PublicationAccountRepository.save(context, id, text(payload, "account"));
        }
        PublicationReminderScheduler.schedule(context, updated);
        return CommandResult.ok("Programmation mise à jour : " + id);
    }

    private static CommandResult createCollection(Context context, JSONObject payload) {
        String name = text(payload, "name");
        if (name.isEmpty()) return CommandResult.fail("Nom de collection manquant.");
        String id = VideoCollectionRepository.create(context, name);
        return id == null
                ? CommandResult.fail("Collection non créée : nom vide ou déjà utilisé.")
                : CommandResult.ok("Collection créée : " + name + " (" + id + ").");
    }

    private static CommandResult renameCollection(Context context, JSONObject payload) {
        VideoCollection collection = findCollection(
                context,
                text(payload, "collection_id"),
                text(payload, "collection")
        );
        if (collection == null) return CommandResult.fail("Collection introuvable.");
        String newName = text(payload, "new_name");
        if (newName.isEmpty()) return CommandResult.fail("Nouveau nom manquant.");
        return VideoCollectionRepository.rename(context, collection.getId(), newName)
                ? CommandResult.ok("Collection renommée en " + newName + ".")
                : CommandResult.fail("Renommage de la collection refusé.");
    }

    private static CommandResult deleteCollection(Context context, JSONObject payload) {
        VideoCollection collection = findCollection(
                context,
                text(payload, "collection_id"),
                text(payload, "collection")
        );
        if (collection == null) return CommandResult.fail("Collection introuvable.");
        if (!VideoCollectionRepository.delete(context, collection.getId())) {
            return CommandResult.fail("Suppression de collection impossible.");
        }
        FolderNoteRepository.deleteCollection(context, collection.getId());
        return CommandResult.ok("Collection supprimée : " + collection.getName() + ".");
    }

    private static CommandResult assignFolder(Context context, JSONObject payload) {
        SavedVideoFolder folder = findFolder(context, text(payload, "folder"));
        if (folder == null) return CommandResult.fail("Dossier introuvable.");

        String collectionId = text(payload, "collection_id");
        String collectionName = text(payload, "collection");
        if (collectionId.isEmpty() && !collectionName.isEmpty()) {
            VideoCollection collection = findCollection(context, "", collectionName);
            if (collection == null) return CommandResult.fail("Collection introuvable.");
            collectionId = collection.getId();
        }

        return VideoCollectionRepository.assignFolder(context, folder.getKey(), collectionId)
                ? CommandResult.ok(collectionId.isEmpty()
                        ? "Dossier retiré de sa collection."
                        : "Dossier affecté à la collection.")
                : CommandResult.fail("Affectation du dossier impossible.");
    }

    private static CommandResult renameFolder(Context context, JSONObject payload) {
        SavedVideoFolder folder = findFolder(context, text(payload, "folder"));
        if (folder == null) return CommandResult.fail("Dossier introuvable.");
        if (VideoFolderUtils.isLegacy(folder.getKey())) {
            return CommandResult.fail("Le dossier racine historique ne peut pas être renommé.");
        }

        String newName = text(payload, "new_name");
        if (newName.isEmpty()) return CommandResult.fail("Nouveau nom manquant.");
        String newKey = VideoFolderUtils.renamedFolderKey(folder.getKey(), newName);
        if (!MediaStoreRepository.renameFolder(context, folder, newKey)) {
            return CommandResult.fail("Android a refusé le renommage du dossier.");
        }
        VideoCollectionRepository.updateFolderKey(context, folder.getKey(), newKey);
        FolderNoteRepository.updateFolderKey(context, folder.getKey(), newKey);
        return CommandResult.ok("Dossier renommé : " + VideoFolderUtils.displayName(newKey) + ".");
    }

    private static CommandResult setFolderNote(Context context, JSONObject payload) {
        SavedVideoFolder folder = findFolder(context, text(payload, "folder"));
        if (folder == null) return CommandResult.fail("Dossier introuvable.");
        FolderNoteRepository.saveFolder(context, folder.getKey(), text(payload, "note"));
        return CommandResult.ok("Note du dossier enregistrée.");
    }

    private static CommandResult setCollectionNote(Context context, JSONObject payload) {
        VideoCollection collection = findCollection(
                context,
                text(payload, "collection_id"),
                text(payload, "collection")
        );
        if (collection == null) return CommandResult.fail("Collection introuvable.");
        FolderNoteRepository.saveCollection(context, collection.getId(), text(payload, "note"));
        return CommandResult.ok("Note de la collection enregistrée.");
    }

    private static CommandResult renameVideo(Context context, JSONObject payload) {
        SavedVideo video = findVideo(context, text(payload, "video_name"));
        if (video == null) return CommandResult.fail("Vidéo introuvable.");
        String newName = text(payload, "new_name");
        if (newName.isEmpty()) return CommandResult.fail("Nouveau nom manquant.");

        String finalName = VideoFolderUtils.safeMp4DisplayName(newName);
        if (!MediaStoreRepository.renameVideo(context, video, finalName)) {
            return CommandResult.fail("Android a refusé le renommage de la vidéo.");
        }
        PublicationScheduleRepository.updateVideoName(
                context,
                video.getUri().toString(),
                finalName
        );
        return CommandResult.ok("Vidéo renommée : " + finalName + ".");
    }

    private static CommandResult deleteVideo(Context context, JSONObject payload) {
        SavedVideo video = findVideo(context, text(payload, "video_name"));
        if (video == null) return CommandResult.fail("Vidéo introuvable.");

        MediaStoreRepository.DeleteResult deleteResult = MediaStoreRepository.deleteVideos(
                context,
                Collections.singletonList(video)
        );
        if (deleteResult.getDeletedCount() != 1) {
            return CommandResult.fail(
                    "Android n'a pas autorisé la suppression du média. Une confirmation système peut être nécessaire."
            );
        }

        List<PublicationSchedule> removed = PublicationScheduleRepository.deleteForVideos(
                context,
                Collections.singletonList(video)
        );
        PublicationReminderScheduler.cancelAll(context, removed);
        return CommandResult.ok("Vidéo supprimée : " + video.getName() + ".");
    }

    private static CommandResult deleteSchedule(Context context, JSONObject payload) {
        String id = text(payload, "schedule_id");
        PublicationSchedule removed = PublicationScheduleRepository.delete(context, id);
        if (removed == null) return CommandResult.fail("Programmation introuvable.");
        PublicationReminderScheduler.cancel(context, removed);
        PublicationAccountRepository.remove(context, id);
        return CommandResult.ok("Programmation supprimée : " + id);
    }

    private static CommandResult markSchedule(Context context, JSONObject payload) {
        String id = text(payload, "schedule_id");
        boolean published = payload.optBoolean("published", true);
        PublicationSchedule updated = PublicationScheduleRepository.setPublished(context, id, published);
        if (updated == null) return CommandResult.fail("Programmation introuvable.");
        if (published) PublicationReminderScheduler.cancel(context, updated);
        else PublicationReminderScheduler.schedule(context, updated);
        return CommandResult.ok(
                published ? "Programmation marquée publiée." : "Programmation remise à publier."
        );
    }

    private static SavedVideo findVideo(Context context, String requestedName) {
        String requested = requestedName == null ? "" : requestedName.trim();
        if (requested.isEmpty()) return null;
        List<SavedVideo> videos = MediaStoreRepository.loadSavedVideos(context);
        for (SavedVideo video : videos) {
            if (video.getName().equalsIgnoreCase(requested)) return video;
        }
        String requestedBase = withoutExtension(requested);
        for (SavedVideo video : videos) {
            if (withoutExtension(video.getName()).equalsIgnoreCase(requestedBase)) return video;
        }
        return null;
    }

    private static SavedVideoFolder findFolder(Context context, String requestedValue) {
        String requested = requestedValue == null ? "" : requestedValue.trim();
        if (requested.isEmpty()) return null;
        for (SavedVideoFolder folder : MediaStoreRepository.loadSavedVideoFolders(context)) {
            if (folder.getKey().equalsIgnoreCase(requested)
                    || VideoFolderUtils.displayName(folder.getKey()).equalsIgnoreCase(requested)) {
                return folder;
            }
        }
        return null;
    }

    private static VideoCollection findCollection(
            Context context,
            String requestedId,
            String requestedName
    ) {
        List<SavedVideoFolder> folders = MediaStoreRepository.loadSavedVideoFolders(context);
        List<VideoCollection> collections = VideoCollectionRepository.list(context, folders);
        String id = requestedId == null ? "" : requestedId.trim();
        String name = requestedName == null ? "" : requestedName.trim();

        for (VideoCollection collection : collections) {
            if (!id.isEmpty() && collection.getId().equals(id)) return collection;
        }
        for (VideoCollection collection : collections) {
            if (!name.isEmpty() && collection.getName().equalsIgnoreCase(name)) return collection;
        }
        return null;
    }

    private static long scheduleTime(JSONObject payload) {
        long millis = payload.optLong("scheduled_at_millis", 0L);
        if (millis > 0L) return millis;

        String date = text(payload, "date");
        String time = text(payload, "time");
        if (date.isEmpty() || time.isEmpty()) return -1L;

        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.FRANCE);
        format.setLenient(false);
        try {
            return format.parse(date + " " + time).getTime();
        } catch (ParseException | NullPointerException ignored) {
            return -1L;
        }
    }

    private static String visibility(JSONObject payload) {
        String value = text(payload, "visibility");
        if (PublicationSchedule.VISIBILITY_PRIVATE.equals(value)
                || PublicationSchedule.VISIBILITY_UNLISTED.equals(value)) {
            return value;
        }
        return PublicationSchedule.VISIBILITY_PUBLIC;
    }

    private static String valueOr(JSONObject payload, String key, String fallback) {
        return payload.has(key) ? text(payload, key) : fallback;
    }

    private static String text(JSONObject payload, String key) {
        return payload == null ? "" : payload.optString(key, "").trim();
    }

    private static String withoutExtension(String name) {
        String safe = name == null ? "" : name.trim();
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
            body.put("status", result.applied ? "applied" : "failed");
            body.put("applied", result.applied ? 1 : 0);
            body.put("failed", result.applied ? 0 : 1);
            body.put("message", result.message);

            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            URL url = new URL(BASE_URL + "/api/device/admin-commands/" + commandId + "/ack");
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
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
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
        final boolean applied;
        final String message;

        private CommandResult(boolean applied, String message) {
            this.applied = applied;
            this.message = message == null ? "" : message;
        }

        static CommandResult ok(String message) {
            return new CommandResult(true, message);
        }

        static CommandResult fail(String message) {
            return new CommandResult(false, message);
        }
    }
}
