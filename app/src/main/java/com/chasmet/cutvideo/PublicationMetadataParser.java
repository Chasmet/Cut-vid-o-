package com.chasmet.cutvideo;

import java.util.Locale;

public final class PublicationMetadataParser {

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
            return new ParsedMetadata("", normalized, "");
        }
        return new ParsedMetadata(
                title.toString().trim(),
                description.toString().trim(),
                hashtags.toString().trim()
        );
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
