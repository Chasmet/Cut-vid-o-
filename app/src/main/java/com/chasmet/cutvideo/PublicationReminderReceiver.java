package com.chasmet.cutvideo;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

public final class PublicationReminderReceiver extends BroadcastReceiver {

    public static final String ACTION_REMIND = "com.chasmet.cutvideo.action.REMIND_PUBLICATION";
    public static final String EXTRA_SCHEDULE_ID = "schedule_id";
    public static final String CHANNEL_ID = "publication_reminders";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_REMIND.equals(intent.getAction())) {
            return;
        }
        String scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID);
        PublicationSchedule schedule = PublicationScheduleRepository.get(context, scheduleId);
        if (schedule == null || schedule.isPublished()) {
            return;
        }
        showNotification(context, schedule);
    }

    public static void ensureNotificationChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(context.getString(R.string.reminder_channel_description));
        channel.enableVibration(true);
        manager.createNotificationChannel(channel);
    }

    private static void showNotification(Context context, PublicationSchedule schedule) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ensureNotificationChannel(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        PendingIntent openSchedule = activityIntent(context, schedule, false, 1);
        PendingIntent publishNow = activityIntent(context, schedule, true, 2);
        String title = context.getString(
                R.string.reminder_notification_title,
                schedule.getPlatform().getDisplayName()
        );
        String videoLabel = schedule.getTitle().isEmpty()
                ? schedule.getVideoName()
                : schedule.getTitle();
        String message = context.getString(
                R.string.reminder_notification_message,
                videoLabel
        );

        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_calendar)
                .setColor(context.getColor(R.color.teal))
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setPriority(Notification.PRIORITY_HIGH)
                .setWhen(schedule.getScheduledAtMillis())
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(openSchedule)
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_share,
                        context.getString(R.string.publish_now),
                        publishNow
                ).build())
                .build();
        try {
            manager.notify(schedule.getId().hashCode(), notification);
        } catch (SecurityException ignored) {
            // L'utilisateur peut retirer l'autorisation de notification à tout moment.
        }
    }

    private static PendingIntent activityIntent(
            Context context,
            PublicationSchedule schedule,
            boolean publishNow,
            int suffix
    ) {
        Intent intent = VideoScheduleActivity.createIntent(
                context,
                schedule.getVideoUri(),
                schedule.getVideoName()
        );
        intent.setData(Uri.parse(
                "cutvideo-schedule://open/" + schedule.getId() + "/" + suffix
        ));
        intent.putExtra(VideoScheduleActivity.EXTRA_FOCUS_SCHEDULE_ID, schedule.getId());
        intent.putExtra(VideoScheduleActivity.EXTRA_PUBLISH_NOW, publishNow);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(
                context,
                schedule.getId().hashCode() + suffix,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
