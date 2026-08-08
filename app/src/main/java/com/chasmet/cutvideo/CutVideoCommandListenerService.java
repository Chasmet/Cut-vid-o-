package com.chasmet.cutvideo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Maintient une courte fenêtre d'écoute quand l'utilisateur quitte Cut Vidéo pour ChatGPT.
 * Cela permet à une commande de programmation envoyée depuis ChatGPT d'être réellement
 * récupérée et écrite dans l'APK sans demander de revenir immédiatement dans l'application.
 */
public final class CutVideoCommandListenerService extends Service {

    private static final String CHANNEL_ID = "cut_video_chatgpt_listener";
    private static final int NOTIFICATION_ID = 24017;
    private static final long POLL_INTERVAL_MS = 5_000L;
    private static final long LISTEN_WINDOW_MS = 5 * 60_000L;
    private static final String SYNC_PREFS = "cut_video_chatgpt_sync";
    private static final String DEVICE_TOKEN_KEY = "device_token";
    private static final String ACTION_STOP = "com.chasmet.cutvideo.STOP_CHATGPT_LISTENER";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long stopAtMillis;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() >= stopAtMillis) {
                stopSelf();
                return;
            }
            SharedPreferences preferences = getSharedPreferences(SYNC_PREFS, MODE_PRIVATE);
            String token = preferences.getString(DEVICE_TOKEN_KEY, "");
            if (token != null && !token.trim().isEmpty()) {
                CutVideoRemoteScheduleSync.pullAsync(CutVideoCommandListenerService.this, token.trim());
            }
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    public static void startListening(Context context) {
        Intent intent = new Intent(context, CutVideoCommandListenerService.class);
        context.startForegroundService(intent);
    }

    public static void stopListening(Context context) {
        Intent intent = new Intent(context, CutVideoCommandListenerService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Cut Vidéo connecté à ChatGPT")
                .setContentText("Écoute temporaire des programmations ChatGPT")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        stopAtMillis = System.currentTimeMillis() + LISTEN_WINDOW_MS;
        handler.removeCallbacks(poll);
        handler.post(poll);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(poll);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Synchronisation ChatGPT",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Permet à Cut Vidéo de recevoir brièvement les programmations ChatGPT en arrière-plan.");
        manager.createNotificationChannel(channel);
    }
}
