package com.chasmet.cutvideo;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class AppUpdateManager {
    private static final String LATEST_RELEASE = "https://api.github.com/repos/Chasmet/Cut-vid-o-/releases/latest";
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private static final String PREFS = "cut_video_update_state";
    private static final String KEY_LAST_AUTO_CHECK = "last_auto_check";
    private static final String KEY_PENDING_VERSION = "pending_version";
    private static final String KEY_PENDING_URL = "pending_url";
    private static final String KEY_MCP_UPDATE_REQUESTED = "mcp_update_requested";
    private static final long AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    public interface Callback {
        void onStatus(String message, boolean updateAvailable);
    }

    private AppUpdateManager() {
    }

    public static void check(Activity activity, boolean interactive, Callback callback) {
        fetchLatest(activity, true, interactive, callback);
    }

    public static void requestUpdateFromMcp(Context context) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MCP_UPDATE_REQUESTED, true)
                .apply();
    }

    public static void consumeMcpUpdateRequest(Activity activity) {
        SharedPreferences preferences = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!preferences.getBoolean(KEY_MCP_UPDATE_REQUESTED, false)) return;
        preferences.edit().remove(KEY_MCP_UPDATE_REQUESTED).apply();
        check(activity, true, (message, updateAvailable) -> {
            // Le dialogue de mise à jour gère directement la suite.
        });
    }

    public static void checkAutomatically(Activity activity) {
        SharedPreferences preferences = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = preferences.getLong(KEY_LAST_AUTO_CHECK, 0L);
        if (now - last < AUTO_CHECK_INTERVAL_MS) return;

        preferences.edit().putLong(KEY_LAST_AUTO_CHECK, now).apply();
        fetchLatest(activity, true, false, (message, updateAvailable) -> {
            // Le contrôle automatique reste silencieux s'il n'y a pas de mise à jour.
        });
    }

    public static void resumePendingUpdate(Activity activity) {
        SharedPreferences preferences = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String version = preferences.getString(KEY_PENDING_VERSION, "");
        String apkUrl = preferences.getString(KEY_PENDING_URL, "");
        if (version == null || version.trim().isEmpty() || apkUrl == null || apkUrl.trim().isEmpty()) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            return;
        }

        preferences.edit()
                .remove(KEY_PENDING_VERSION)
                .remove(KEY_PENDING_URL)
                .apply();
        downloadAndInstall(activity, version.trim(), apkUrl.trim());
    }

    private static void fetchLatest(Activity activity,
                                    boolean showUpdateDialog,
                                    boolean showNoUpdateDialog,
                                    Callback callback) {
        callback.onStatus("Recherche de mise à jour…", false);

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(LATEST_RELEASE).openConnection();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(15_000);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                connection.setRequestProperty("User-Agent", "Cut-Video-Android/" + BuildConfig.VERSION_NAME);

                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("GitHub HTTP " + code);
                }

                StringBuilder json = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) json.append(line);
                }

                JSONObject release = new JSONObject(json.toString());
                String tag = release.optString("tag_name", "").trim();
                String latest = tag.startsWith("v") ? tag.substring(1) : tag;
                String apkUrl = findApk(release.optJSONArray("assets"));
                String current = BuildConfig.VERSION_NAME;

                boolean newer = compareVersions(latest, current) > 0 && !apkUrl.isEmpty();
                activity.runOnUiThread(() -> {
                    if (newer) {
                        callback.onStatus("Nouvelle version v" + latest + " disponible", true);
                        if (showUpdateDialog) showUpdateDialog(activity, latest, apkUrl);
                    } else {
                        callback.onStatus("Cut Vidéo est à jour — v" + current, false);
                        if (showNoUpdateDialog) {
                            new AlertDialog.Builder(activity)
                                    .setTitle("Mise à jour")
                                    .setMessage("Tu utilises déjà la dernière version disponible : v" + current)
                                    .setPositiveButton("OK", null)
                                    .show();
                        }
                    }
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    callback.onStatus("Vérification impossible. Vérifie ta connexion puis réessaie.", false);
                    if (showNoUpdateDialog) {
                        new AlertDialog.Builder(activity)
                                .setTitle("Mise à jour")
                                .setMessage("Impossible de contacter GitHub pour le moment.")
                                .setPositiveButton("OK", null)
                                .show();
                    }
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "cut-video-update-check").start();
    }

    private static String findApk(JSONArray assets) {
        if (assets == null) return "";
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "").toLowerCase();
            if (!name.endsWith(".apk")) continue;
            String url = asset.optString("browser_download_url", "").trim();
            if (!url.isEmpty()) return url;
        }
        return "";
    }

    private static void showUpdateDialog(Activity activity, String version, String apkUrl) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        new AlertDialog.Builder(activity)
                .setTitle("Mise à jour disponible")
                .setMessage("Cut Vidéo v" + version
                        + " est disponible. L'APK sera téléchargé puis Android ouvrira l'installation. "
                        + "Une mise à jour normale conserve les vidéos, dossiers, métadonnées et programmations.")
                .setNegativeButton("Plus tard", null)
                .setPositiveButton("METTRE À JOUR", (dialog, which) ->
                        requestPermissionOrDownload(activity, version, apkUrl))
                .show();
    }

    private static void requestPermissionOrDownload(Activity activity, String version, String apkUrl) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PENDING_VERSION, version)
                    .putString(KEY_PENDING_URL, apkUrl)
                    .apply();

            try {
                Intent permission = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())
                );
                activity.startActivity(permission);
            } catch (ActivityNotFoundException error) {
                activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            }

            Toast.makeText(
                    activity,
                    "Autorise Cut Vidéo à installer des applications, puis reviens dans l'application.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        downloadAndInstall(activity, version, apkUrl);
    }

    private static void downloadAndInstall(Activity activity, String version, String apkUrl) {
        DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            Toast.makeText(activity, "Service de téléchargement indisponible.", Toast.LENGTH_LONG).show();
            return;
        }

        File directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) {
            Toast.makeText(activity, "Dossier de téléchargement indisponible.", Toast.LENGTH_LONG).show();
            return;
        }

        String fileName = "Cut-Video-v" + version + ".apk";
        File apkFile = new File(directory, fileName);
        if (apkFile.exists() && !apkFile.delete()) {
            Toast.makeText(activity, "Impossible de remplacer l'ancien fichier de mise à jour.", Toast.LENGTH_LONG).show();
            return;
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("Mise à jour Cut Vidéo")
                .setDescription("Téléchargement de la version " + version)
                .setMimeType(APK_MIME)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName);

        final long downloadId;
        try {
            downloadId = manager.enqueue(request);
        } catch (Exception error) {
            Toast.makeText(activity, "Le téléchargement n'a pas pu démarrer.", Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(activity, "Téléchargement de Cut Vidéo v" + version + "…", Toast.LENGTH_LONG).show();

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                if (completedId != downloadId) return;

                try {
                    activity.unregisterReceiver(this);
                } catch (Exception ignored) {
                }

                int status = getDownloadStatus(manager, downloadId);
                if (status != DownloadManager.STATUS_SUCCESSFUL) {
                    Toast.makeText(activity, "Échec du téléchargement de la mise à jour.", Toast.LENGTH_LONG).show();
                    return;
                }

                if (!apkFile.exists() || apkFile.length() <= 0L) {
                    Toast.makeText(activity, "APK téléchargé introuvable.", Toast.LENGTH_LONG).show();
                    return;
                }

                openInstaller(activity, apkFile);
            }
        };

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        ContextCompat.registerReceiver(
                activity,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
        );
    }

    private static int getDownloadStatus(DownloadManager manager, long downloadId) {
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor cursor = manager.query(query)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                if (index >= 0) return cursor.getInt(index);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static void openInstaller(Activity activity, File apkFile) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    apkFile
            );

            Intent install = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(install);
        } catch (Exception error) {
            Toast.makeText(activity, "Impossible d'ouvrir l'installation Android.", Toast.LENGTH_LONG).show();
        }
    }

    static int compareVersions(String left, String right) {
        if (left == null || left.isEmpty()) return -1;
        if (right == null) right = "";

        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        int length = Math.max(a.length, b.length);

        for (int i = 0; i < length; i++) {
            int av = i < a.length ? numericPart(a[i]) : 0;
            int bv = i < b.length ? numericPart(b[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int numericPart(String value) {
        String digits = value.replaceAll("[^0-9].*$", "");
        if (digits.isEmpty()) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
