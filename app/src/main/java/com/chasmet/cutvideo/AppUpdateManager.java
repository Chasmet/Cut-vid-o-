package com.chasmet.cutvideo;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.appcompat.app.AlertDialog;
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

    public interface Callback {
        void onStatus(String message, boolean updateAvailable);
    }

    private AppUpdateManager() {}

    public static void check(Activity activity, boolean interactive, Callback callback) {
        callback.onStatus("Recherche de mise à jour…", false);
        new Thread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "Cut-Video-Android");
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("GitHub HTTP " + code);

                StringBuilder json = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
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
                        callback.onStatus("Nouvelle version " + latest + " disponible", true);
                        if (interactive) showUpdateDialog(activity, latest, apkUrl);
                    } else {
                        callback.onStatus("Cut Vidéo est à jour — v" + current, false);
                        if (interactive) new AlertDialog.Builder(activity)
                                .setTitle("Mise à jour")
                                .setMessage("Tu utilises déjà la dernière version : v" + current)
                                .setPositiveButton("OK", null)
                                .show();
                    }
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> callback.onStatus("Vérification impossible. Réessaie plus tard.", false));
            }
        }).start();
    }

    private static String findApk(JSONArray assets) {
        if (assets == null) return "";
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "").toLowerCase();
            if (name.endsWith(".apk")) return asset.optString("browser_download_url", "");
        }
        return "";
    }

    private static void showUpdateDialog(Activity activity, String version, String apkUrl) {
        new AlertDialog.Builder(activity)
                .setTitle("Mise à jour disponible")
                .setMessage("Cut Vidéo v" + version + " est disponible. L'application va télécharger l'APK puis ouvrir l'installation Android. Tes vidéos, dossiers et programmations restent conservés.")
                .setNegativeButton("Plus tard", null)
                .setPositiveButton("METTRE À JOUR", (dialog, which) -> downloadAndInstall(activity, version, apkUrl))
                .show();
    }

    private static void downloadAndInstall(Activity activity, String version, String apkUrl) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
            Intent permission = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(permission);
            new AlertDialog.Builder(activity)
                    .setTitle("Autorisation requise")
                    .setMessage("Autorise Cut Vidéo à installer les mises à jour, puis reviens dans Réglages et relance la vérification.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        String fileName = "Cut-Video-v" + version + ".apk";
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("Mise à jour Cut Vidéo")
                .setDescription("Téléchargement de la version " + version)
                .setMimeType(APK_MIME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName);

        DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        long downloadId = manager.enqueue(request);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return;
                try { activity.unregisterReceiver(this); } catch (Exception ignored) {}
                File apk = new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName);
                if (!apk.exists()) return;
                Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", apk);
                Intent install = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, APK_MIME)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(install);
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(receiver, filter);
    }

    static int compareVersions(String left, String right) {
        if (left == null || left.isEmpty()) return -1;
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
        try { return Integer.parseInt(digits); } catch (NumberFormatException ignored) { return 0; }
    }
}
