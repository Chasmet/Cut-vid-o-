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
import java.util.List;

public final class MediaStoreRepository {

    public static final String FOLDER_NAME = "CutVideo";
    public static final String RELATIVE_FOLDER = Environment.DIRECTORY_MOVIES + "/" + FOLDER_NAME + "/";

    private MediaStoreRepository() {
    }

    public static Uri publishMp4(Context context, File sourceFile, String displayName)
            throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_FOLDER);
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
                MediaStore.Video.Media.SIZE
        };
        String selection = MediaStore.Video.Media.RELATIVE_PATH + " = ? AND "
                + MediaStore.Video.Media.IS_PENDING + " = 0";
        String[] arguments = {RELATIVE_FOLDER};
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

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                Uri uri = ContentUris.withAppendedId(collection, id);
                results.add(new SavedVideo(
                        uri,
                        cursor.getString(nameColumn),
                        cursor.getLong(durationColumn),
                        cursor.getLong(sizeColumn)
                ));
            }
        } catch (RuntimeException ignored) {
            // Un stockage momentanément indisponible donne simplement une liste vide.
        }
        return results;
    }
}

