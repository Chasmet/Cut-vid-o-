package com.chasmet.cutvideo;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Logique pure de nommage et de lecture des sous-dossiers créés pour chaque traitement. */
public final class VideoFolderUtils {

    public static final String LEGACY_FOLDER_KEY = "__legacy__";

    private static final Pattern GENERATED_FOLDER = Pattern.compile(
            "^CutVideo_(.+)_(\\d{8})_(\\d{6})$"
    );
    private static final DateTimeFormatter STORED_DATE = DateTimeFormatter.ofPattern(
            "yyyyMMdd_HHmmss",
            Locale.FRANCE
    );
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern(
            "dd/MM/yyyy 'à' HH:mm",
            Locale.FRANCE
    );

    private VideoFolderUtils() {
    }

    public static String folderKey(String relativePath, String baseRelativeFolder) {
        if (relativePath == null || baseRelativeFolder == null) {
            return LEGACY_FOLDER_KEY;
        }
        String base = ensureTrailingSlash(baseRelativeFolder);
        String path = relativePath.replace('\\', '/');
        if (!path.startsWith(base)) {
            return LEGACY_FOLDER_KEY;
        }
        String remainder = trimSlashes(path.substring(base.length()));
        if (remainder.isEmpty()) {
            return LEGACY_FOLDER_KEY;
        }
        int nestedSeparator = remainder.indexOf('/');
        return nestedSeparator >= 0 ? remainder.substring(0, nestedSeparator) : remainder;
    }

    public static boolean isLegacy(String folderKey) {
        return LEGACY_FOLDER_KEY.equals(folderKey);
    }

    public static String displayName(String folderKey) {
        Matcher matcher = GENERATED_FOLDER.matcher(folderKey == null ? "" : folderKey);
        String encodedName = matcher.matches() ? matcher.group(1) : folderKey;
        if (encodedName == null || encodedName.trim().isEmpty()) {
            return "Vidéo";
        }
        String readable = encodedName.replace('_', ' ').trim();
        return readable.isEmpty() ? "Vidéo" : readable;
    }

    public static String displayDate(String folderKey) {
        Matcher matcher = GENERATED_FOLDER.matcher(folderKey == null ? "" : folderKey);
        if (!matcher.matches()) {
            return "";
        }
        try {
            LocalDateTime date = LocalDateTime.parse(
                    matcher.group(2) + "_" + matcher.group(3),
                    STORED_DATE
            );
            return DISPLAY_DATE.format(date);
        } catch (DateTimeParseException ignored) {
            return "";
        }
    }

    public static String safeFolderName(String candidate) {
        String value = candidate == null ? "" : candidate;
        value = value
                .replaceAll("[\\\\/:*?\"<>|]+", "_")
                .replaceAll("\\s+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        if (value.isEmpty()) {
            value = "CutVideo_traitement";
        }
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    public static String normalizeUserName(String candidate) {
        String value = candidate == null ? "" : candidate;
        value = value
                .replaceAll("[\\p{Cntrl}]+", "")
                .replaceAll("\\s+", " ")
                .trim();
        return value.length() > 60 ? value.substring(0, 60).trim() : value;
    }

    public static String renamedFolderKey(String currentFolderKey, String requestedName) {
        String normalizedName = normalizeUserName(requestedName);
        String encodedName = safeFolderName(normalizedName);
        if (encodedName.length() > 48) {
            encodedName = encodedName.substring(0, 48).replaceAll("_+$", "");
        }

        Matcher matcher = GENERATED_FOLDER.matcher(
                currentFolderKey == null ? "" : currentFolderKey
        );
        String timestamp = matcher.matches()
                ? matcher.group(2) + "_" + matcher.group(3)
                : STORED_DATE.format(LocalDateTime.now());
        return safeFolderName("CutVideo_" + encodedName + "_" + timestamp);
    }

    public static String editableVideoName(String currentName) {
        if (currentName == null) {
            return "";
        }
        return currentName.replaceFirst("(?i)\\.mp4$", "");
    }

    public static String safeMp4DisplayName(String requestedName) {
        String value = editableVideoName(normalizeUserName(requestedName))
                .replaceAll("[\\\\/:*?\"<>|]+", "_")
                .replaceAll("\\s*_\\s*", "_")
                .replaceAll("^[ ._]+|[ ._]+$", "");
        if (value.isEmpty()) {
            value = "video";
        }
        if (value.length() > 92) {
            value = value.substring(0, 92).replaceAll("[ ._]+$", "");
        }
        return value + ".mp4";
    }

    private static String ensureTrailingSlash(String value) {
        String normalized = value.replace('\\', '/');
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    private static String trimSlashes(String value) {
        return value.replaceAll("^/+|/+$", "");
    }
}
