package com.chasmet.cutvideo;

public final class FolderNote {

    private final String text;
    private final long updatedAtMillis;

    public FolderNote(String text, long updatedAtMillis) {
        this.text = text == null ? "" : text;
        this.updatedAtMillis = Math.max(0L, updatedAtMillis);
    }

    public String getText() {
        return text;
    }

    public long getUpdatedAtMillis() {
        return updatedAtMillis;
    }

    public boolean isEmpty() {
        return text.trim().isEmpty();
    }
}
