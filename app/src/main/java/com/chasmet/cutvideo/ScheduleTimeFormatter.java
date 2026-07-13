package com.chasmet.cutvideo;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class ScheduleTimeFormatter {

    private ScheduleTimeFormatter() {
    }

    public static String dateTime(long timeMillis) {
        return new SimpleDateFormat(
                "EEE d MMM yyyy • HH:mm",
                Locale.getDefault()
        ).format(new Date(timeMillis));
    }

    public static String date(long timeMillis) {
        return new SimpleDateFormat(
                "EEE d MMM yyyy",
                Locale.getDefault()
        ).format(new Date(timeMillis));
    }

    public static String time(long timeMillis) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(timeMillis));
    }
}
