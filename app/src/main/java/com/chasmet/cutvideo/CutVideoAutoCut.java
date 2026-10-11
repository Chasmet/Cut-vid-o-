package com.chasmet.cutvideo;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * Decoupage explicite a la demande : l'app conserve uniquement l'URI de la
 * derniere video choisie par l'utilisateur. Aucune exploration de la galerie.
 * La commande est consommee une seule fois lors du retour au premier plan.
 */
final class CutVideoAutoCut {
    private static final String PREFS = "cut_video_automatic_cut";
    private static final String LAST_URI = "last_import_uri";
    private static final String LAST_NAME = "last_import_name";
    private static final String PENDING_SECONDS = "pending_seconds";
    private static final String PENDING_AT = "pending_created_at";
    private static final long PENDING_TTL_MS = 24L * 60L * 60L * 1000L;

    private CutVideoAutoCut() { }

    static void rememberImport(Context context, Uri uri, String name) {
        if (uri == null) return;
        preferences(context).edit()
                .putString(LAST_URI, uri.toString())
                .putString(LAST_NAME, name == null ? "" : name)
                .remove(PENDING_SECONDS)
                .remove(PENDING_AT)
                .apply();
    }

    static String queue(Context context, int seconds, String requestedName) {
        if (seconds < 1 || seconds > 600) {
            throw new IllegalArgumentException("La duree doit etre comprise entre 1 et 600 secondes.");
        }
        SharedPreferences preferences = preferences(context);
        String uri = preferences.getString(LAST_URI, "");
        String name = preferences.getString(LAST_NAME, "");
        if (uri == null || uri.trim().isEmpty() || name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Aucune video source importee. Choisissez d'abord une video dans Cut Video.");
        }
        if (requestedName != null && !requestedName.trim().isEmpty()
                && !name.equalsIgnoreCase(requestedName.trim())) {
            throw new IllegalArgumentException("La derniere video importee est " + name
                    + ", pas " + requestedName.trim() + ". Decoupage refuse.");
        }
        int oldSeconds = preferences.getInt(PENDING_SECONDS, 0);
        long oldTimestamp = preferences.getLong(PENDING_AT, 0L);
        if (oldSeconds > 0 && System.currentTimeMillis() - oldTimestamp < PENDING_TTL_MS) {
            throw new IllegalArgumentException("Un decoupage est deja en attente d'ouverture.");
        }
        boolean persisted = preferences.edit()
                .putInt(PENDING_SECONDS, seconds)
                .putLong(PENDING_AT, System.currentTimeMillis())
                .commit();
        if (!persisted) throw new IllegalStateException("Enregistrement de la commande impossible.");
        return name;
    }

    static Pending consume(Context context) {
        SharedPreferences preferences = preferences(context);
        int seconds = preferences.getInt(PENDING_SECONDS, 0);
        if (seconds <= 0) return null;
        long createdAt = preferences.getLong(PENDING_AT, 0L);
        String uriValue = preferences.getString(LAST_URI, "");
        String name = preferences.getString(LAST_NAME, "");
        preferences.edit().remove(PENDING_SECONDS).remove(PENDING_AT).commit();
        if (seconds > 600 || createdAt <= 0
                || System.currentTimeMillis() - createdAt > PENDING_TTL_MS
                || uriValue == null || uriValue.isEmpty()) {
            return null;
        }
        return new Pending(Uri.parse(uriValue), name == null ? "" : name, seconds);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static final class Pending {
        final Uri sourceUri;
        final String sourceName;
        final int segmentSeconds;

        Pending(Uri sourceUri, String sourceName, int segmentSeconds) {
            this.sourceUri = sourceUri;
            this.sourceName = sourceName;
            this.segmentSeconds = segmentSeconds;
        }
    }
}
