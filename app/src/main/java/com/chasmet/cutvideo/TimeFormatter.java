package com.chasmet.cutvideo;

import java.util.Locale;

public final class TimeFormatter {

    private TimeFormatter() {
    }

    public static String duration(long durationMs) {
        long totalSeconds = Math.max(0, durationMs) / 1_000L;
        long hours = totalSeconds / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format(Locale.FRANCE, "%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.FRANCE, "%02d:%02d", minutes, seconds);
    }

    public static String fileSize(long bytes) {
        if (bytes < 1_024L) {
            return bytes + " o";
        }
        double kilobytes = bytes / 1_024d;
        if (kilobytes < 1_024d) {
            return String.format(Locale.FRANCE, "%.0f Ko", kilobytes);
        }
        double megabytes = kilobytes / 1_024d;
        if (megabytes < 1_024d) {
            return String.format(Locale.FRANCE, "%.1f Mo", megabytes);
        }
        return String.format(Locale.FRANCE, "%.2f Go", megabytes / 1_024d);
    }
}

