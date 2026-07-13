package com.chasmet.cutvideo;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import java.util.List;

public final class PublicationReminderScheduler {

    private static final String REMINDER_SCHEME = "cutvideo-reminder";

    private PublicationReminderScheduler() {
    }

    public static void schedule(Context context, PublicationSchedule schedule) {
        cancel(context, schedule);
        if (schedule.isPublished() || schedule.getScheduledAtMillis() <= System.currentTimeMillis()) {
            return;
        }

        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        if (alarmManager == null) {
            return;
        }
        PendingIntent pendingIntent = reminderIntent(
                context,
                schedule,
                PendingIntent.FLAG_UPDATE_CURRENT
        );
        if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        schedule.getScheduledAtMillis(),
                        pendingIntent
                );
                return;
            } catch (SecurityException ignored) {
                // L'autorisation peut être retirée entre le contrôle et la programmation.
            }
        }
        alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                schedule.getScheduledAtMillis(),
                pendingIntent
        );
    }

    public static void cancel(Context context, PublicationSchedule schedule) {
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        if (alarmManager != null) {
            PendingIntent pendingIntent = reminderIntent(
                    context,
                    schedule,
                    PendingIntent.FLAG_NO_CREATE
            );
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent);
                pendingIntent.cancel();
            }
        }
        NotificationManager notificationManager = context.getSystemService(
                NotificationManager.class
        );
        if (notificationManager != null) {
            notificationManager.cancel(schedule.getId().hashCode());
        }
    }

    public static void cancelAll(Context context, List<PublicationSchedule> schedules) {
        for (PublicationSchedule schedule : schedules) {
            cancel(context, schedule);
        }
    }

    public static void rescheduleAll(Context context) {
        for (PublicationSchedule schedule : PublicationScheduleRepository.listAll(context)) {
            schedule(context, schedule);
        }
    }

    private static PendingIntent reminderIntent(
            Context context,
            PublicationSchedule schedule,
            int lookupFlag
    ) {
        Intent intent = new Intent(context, PublicationReminderReceiver.class)
                .setAction(PublicationReminderReceiver.ACTION_REMIND)
                .setData(Uri.parse(REMINDER_SCHEME + "://schedule/" + schedule.getId()))
                .putExtra(PublicationReminderReceiver.EXTRA_SCHEDULE_ID, schedule.getId());
        int flags = lookupFlag | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(
                context,
                schedule.getId().hashCode(),
                intent,
                flags
        );
    }
}
