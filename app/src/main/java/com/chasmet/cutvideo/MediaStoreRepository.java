package com.chasmet.cutvideo;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MediaStoreRepository {

    public static final String FOLDER_NAME = "CutVideo";
    public static final String RELATIVE_FOLDER = Environment.DIRECTORY_MOVIES + "/" + FOLDER_NAME + "/";

    private MediaStoreRepository() {
    }

    public static Uri publishMp4(
            Context context,
            File sourceFile,
            String displayName,
            String workFolderName
    )
            throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        String safeWorkFolder = VideoFolderUtils.safeFolderName(workFolderName);
        values.put(
                MediaStore.Video.Media.RELATIVE_PATH,
                RELATIVE_FOLDER + safeWorkFolder + "/"
        );
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        values.put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1_000L);

        Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri destination = resolver.insert(collection, values);
        if (destination == null) {
            throw new IOException("Android n'a pas créé le fichier de destination.");
        }

        try (InputStream input = new FileInputStream(sourceFile);
             OutputStream output = resolver.openOutputStream(destination, "w")) {
            if (output == null) {
                throw new IOException("Le stockage du téléphone n'est pas accessible.");
            }
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();

            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(destination, ready, null, null);
            return destination;
        } catch (IOException | RuntimeException error) {
            resolver.delete(destination, null, null);
            if (error instanceof IOException) {
                throw (IOException) error;
            }
            throw new IOException(error.getMessage(), error);
        }
    }

    public static List<SavedVideo> loadSavedVideos(Context context) {
        List<SavedVideo> results = new ArrayList<>();
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.RELATIVE_PATH,
                MediaStore.Video.Media.DATE_ADDED
        };
        String selection = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ? AND "
                + MediaStore.Video.Media.IS_PENDING + " = 0";
        String[] arguments = {RELATIVE_FOLDER + "%"};
        String order = MediaStore.Video.Media.DATE_ADDED + " DESC";
        Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

        try (Cursor cursor = context.getContentResolver().query(
                collection,
                projection,
                selection,
                arguments,
                order
        )) {
            if (cursor == null) {
                return results;
            }
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
            int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH);
            int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                Uri uri = ContentUris.withAppendedId(collection, id);
                results.add(new SavedVideo(
                        uri,
                        cursor.getString(nameColumn),
                        cursor.getLong(durationColumn),
                        cursor.getLong(sizeColumn),
                        cursor.getString(pathColumn),
                        cursor.getLong(dateColumn)
                ));
            }
        } catch (RuntimeException ignored) {
            // Un stockage momentanément indisponible donne simplement une liste vide.
        }
        return results;
    }

    public static List<SavedVideoFolder> loadSavedVideoFolders(Context context) {
        Map<String, List<SavedVideo>> groupedVideos = new LinkedHashMap<>();
        for (SavedVideo video : loadSavedVideos(context)) {
            String folderKey = VideoFolderUtils.folderKey(
                    video.getRelativePath(),
                    RELATIVE_FOLDER
            );
            groupedVideos
                    .computeIfAbsent(folderKey, ignored -> new ArrayList<>())
                    .add(video);
        }

        List<SavedVideoFolder> folders = new ArrayList<>();
        for (Map.Entry<String, List<SavedVideo>> entry : groupedVideos.entrySet()) {
            folders.add(new SavedVideoFolder(entry.getKey(), entry.getValue()));
        }
        return folders;
    }

    public static boolean renameFolder(
            Context context,
            SavedVideoFolder folder,
            String newFolderKey
    ) {
        String newRelativePath = RELATIVE_FOLDER
                + VideoFolderUtils.safeFolderName(newFolderKey)
                + "/";
        ContentResolver resolver = context.getContentResolver();
        List<SavedVideo> movedVideos = new ArrayList<>();

        try {
            for (SavedVideo video : folder.getVideos()) {
                if (newRelativePath.equals(video.getRelativePath())) {
                    continue;
                }
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.RELATIVE_PATH, newRelativePath);
                if (resolver.update(video.getUri(), values, null, null) <= 0) {
                    throw new IllegalStateException("Le dossier n'a pas été renommé.");
                }
                movedVideos.add(video);
            }
            return true;
        } catch (RuntimeException error) {
            for (SavedVideo movedVideo : movedVideos) {
                String originalPath = movedVideo.getRelativePath();
                if (originalPath == null || originalPath.trim().isEmpty()) {
                    continue;
                }
                try {
                    ContentValues rollback = new ContentValues();
                    rollback.put(MediaStore.Video.Media.RELATIVE_PATH, originalPath);
                    resolver.update(movedVideo.getUri(), rollback, null, null);
                } catch (RuntimeException ignored) {
                    // Le prochain chargement reflétera l'état réel si Android refuse le retour.
                }
            }
            return false;
        }
    }

    public static boolean renameVideo(Context context, SavedVideo video, String requestedName) {
        ContentValues values = new ContentValues();
        values.put(
                MediaStore.Video.Media.DISPLAY_NAME,
                VideoFolderUtils.safeMp4DisplayName(requestedName)
        );
        try {
            return context.getContentResolver().update(
                    video.getUri(),
                    values,
                    null,
                    null
            ) > 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
