package com.chasmet.cutvideo;

import java.util.Locale;

public enum SocialPlatform {
    YOUTUBE("youtube", "YouTube", "com.google.android.youtube"),
    TIKTOK("tiktok", "TikTok", "com.zhiliaoapp.musically"),
    INSTAGRAM("instagram", "Instagram", "com.instagram.android"),
    X("x", "X", "com.twitter.android"),
    FACEBOOK("facebook", "Facebook", "com.facebook.katana"),
    OTHER("other", "Autre application", null);

    private final String key;
    private final String displayName;
    private final String packageName;

    SocialPlatform(String key, String displayName, String packageName) {
        this.key = key;
        this.displayName = displayName;
        this.packageName = packageName;
    }

    public String getKey() {
        return key;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPackageName() {
        return packageName;
    }

    public static SocialPlatform fromKey(String key) {
        if (key != null) {
            String normalized = key.trim().toLowerCase(Locale.ROOT);
            for (SocialPlatform platform : values()) {
                if (platform.key.equals(normalized)) {
                    return platform;
                }
            }
        }
        return OTHER;
    }
}
