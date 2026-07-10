package com.chasmet.cutvideo;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class VideoUtils {

    private VideoUtils() {
    }

    public static long readDuration(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String rawDuration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
            );
            return rawDuration == null ? 0L : Long.parseLong(rawDuration);
        } catch (RuntimeException error) {
            return 0L;
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
                // Certains codecs défectueux peuvent aussi échouer pendant la libération.
            }
        }
    }

    public static String displayName(Context context, Uri uri) {
        String result = null;
        try (Cursor cursor = context.getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    result = cursor.getString(column);
                }
            }
        } catch (RuntimeException ignored) {
            // Le nom est seulement décoratif, l'URI reste suffisante pour travailler.
        }
        return result == null || result.trim().isEmpty() ? "video.mp4" : result;
    }

    public static String outputBaseName(String sourceName) {
        String withoutExtension = sourceName.replaceFirst("(?i)\\.[a-z0-9]{2,5}$", "");
        String safeName = withoutExtension
                .replaceAll("[^a-zA-Z0-9À-ÿ_-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        if (safeName.isEmpty()) {
            safeName = "Video";
        }
        if (safeName.length() > 32) {
            safeName = safeName.substring(0, 32);
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE)
                .format(new Date());
        return "CutVideo_" + safeName + "_" + timestamp;
    }
}
