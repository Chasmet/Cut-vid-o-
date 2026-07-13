package com.chasmet.cutvideo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class PublicationMetadataParser {

    private static final Pattern HASHTAG_ONLY_LINE = Pattern.compile(
            "^(?:#[\\p{L}\\p{M}\\p{N}_]+(?:\\s+|$))+$"
    );

    private PublicationMetadataParser() {
    }

    public static ParsedMetadata parse(String rawText) {
        String normalized = rawText == null ? "" : rawText.replace("\r", "").trim();
        if (normalized.isEmpty()) {
            return new ParsedMetadata("", "", "");
        }

        StringBuilder title = new StringBuilder();
        StringBuilder description = new StringBuilder();
        StringBuilder hashtags = new StringBuilder();
        Section currentSection = Section.NONE;
        boolean foundLabel = false;

        for (String rawLine : normalized.split("\n", -1)) {
            LabelledLine labelledLine = labelledLine(rawLine);
            if (labelledLine != null) {
                foundLabel = true;
                currentSection = labelledLine.section;
                append(currentSection, labelledLine.value, title, description, hashtags);
            } else if (currentSection != Section.NONE) {
                append(currentSection, rawLine.trim(), title, description, hashtags);
            }
        }

        if (!foundLabel) {
            return parseUnlabelled(normalized);
        }
        return new ParsedMetadata(
                title.toString().trim(),
                description.toString().trim(),
                hashtags.toString().trim()
        );
    }

    private static ParsedMetadata parseUnlabelled(String normalized) {
        String[] lines = normalized.split("\\n", -1);
        int contentStart = 0;
        int contentEnd = lines.length;

        while (contentStart < contentEnd && lines[contentStart].trim().isEmpty()) {
            contentStart++;
        }
        while (contentEnd > contentStart && lines[contentEnd - 1].trim().isEmpty()) {
            contentEnd--;
        }

        List<String> hashtagLines = new ArrayList<>();
        int bodyEnd = contentEnd;
        boolean foundHashtags = false;
        while (bodyEnd > contentStart) {
            String line = lines[bodyEnd - 1].trim();
            if (HASHTAG_ONLY_LINE.matcher(line).matches()) {
                hashtagLines.add(line);
                foundHashtags = true;
                bodyEnd--;
            } else if (foundHashtags && line.isEmpty()) {
                bodyEnd--;
            } else {
                break;
            }
        }
        Collections.reverse(hashtagLines);

        while (bodyEnd > contentStart && lines[bodyEnd - 1].trim().isEmpty()) {
            bodyEnd--;
        }

        String hashtags = String.join(" ", hashtagLines);
        if (bodyEnd <= contentStart) {
            return new ParsedMetadata("", "", hashtags);
        }

        String title = lines[contentStart].trim();
        String description = joinLines(lines, contentStart + 1, bodyEnd).trim();
        return new ParsedMetadata(title, description, hashtags);
    }

    private static String joinLines(String[] lines, int start, int end) {
        StringBuilder joined = new StringBuilder();
        for (int index = start; index < end; index++) {
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(lines[index]);
        }
        return joined.toString();
    }

    private static LabelledLine labelledLine(String rawLine) {
        String cleaned = rawLine == null
                ? ""
                : rawLine.trim().replace("**", "").replace("__", "");
        int separator = cleaned.indexOf(':');
        if (separator < 0) {
            return null;
        }

        String label = cleaned.substring(0, separator)
                .replace("#", "")
                .trim()
                .toLowerCase(Locale.ROOT);
        String value = cleaned.substring(separator + 1).trim();
        if (label.equals("titre")
                || label.equals("title")
                || label.equals("titre de la vidéo")
                || label.equals("titre de la video")) {
            return new LabelledLine(Section.TITLE, value);
        }
        if (label.equals("description")
                || label.equals("légende")
                || label.equals("legende")
                || label.equals("caption")) {
            return new LabelledLine(Section.DESCRIPTION, value);
        }
        if (label.equals("hashtags")
                || label.equals("hashtag")
                || label.equals("mots-clés")
                || label.equals("mots cles")
                || label.equals("keywords")) {
            return new LabelledLine(Section.HASHTAGS, value);
        }
        return null;
    }

    private static void append(
            Section section,
            String value,
            StringBuilder title,
            StringBuilder description,
            StringBuilder hashtags
    ) {
        StringBuilder target;
        if (section == Section.TITLE) {
            target = title;
        } else if (section == Section.HASHTAGS) {
            target = hashtags;
        } else {
            target = description;
        }

        if (value.isEmpty()) {
            if (target.length() > 0 && section == Section.DESCRIPTION) {
                target.append('\n');
            }
            return;
        }
        if (target.length() > 0) {
            target.append(section == Section.DESCRIPTION ? '\n' : ' ');
        }
        target.append(value);
    }

    public static final class ParsedMetadata {
        private final String title;
        private final String description;
        private final String hashtags;

        private ParsedMetadata(String title, String description, String hashtags) {
            this.title = title;
            this.description = description;
            this.hashtags = hashtags;
        }

        public String getTitle() {
            return title;
        }

        public String getDescription() {
            return description;
        }

        public String getHashtags() {
            return hashtags;
        }
    }

    private enum Section {
        NONE,
        TITLE,
        DESCRIPTION,
        HASHTAGS
    }

    private static final class LabelledLine {
        private final Section section;
        private final String value;

        private LabelledLine(Section section, String value) {
            this.section = section;
            this.value = value;
        }
    }
}
