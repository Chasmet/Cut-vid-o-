package com.chasmet.cutvideo;

import android.content.Context;
import android.content.SharedPreferences;

public final class PublicationAccountRepository {

    private static final String PREFERENCES_NAME = "publication_accounts";

    private PublicationAccountRepository() {
    }

    public static void save(Context context, String scheduleId, String account) {
        if (context == null || scheduleId == null || scheduleId.trim().isEmpty()) {
            return;
        }
        String normalized = normalize(account);
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(scheduleId, normalized)
                .apply();
    }

    public static String get(Context context, String scheduleId) {
        if (context == null || scheduleId == null || scheduleId.trim().isEmpty()) {
            return "";
        }
        SharedPreferences preferences = context.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
        );
        return normalize(preferences.getString(scheduleId, ""));
    }

    public static void remove(Context context, String scheduleId) {
        if (context == null || scheduleId == null) {
            return;
        }
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(scheduleId)
                .apply();
    }

    private static String normalize(String account) {
        if (account == null) {
            return "";
        }
        String value = account.trim().toLowerCase();
        if ("qg".equals(value)) {
            return "QG";
        }
        if ("chknoirshadow".equals(value) || "chk noir shadow".equals(value)) {
            return "CHKNOIRSHADOW";
        }
        return account.trim();
    }
}
