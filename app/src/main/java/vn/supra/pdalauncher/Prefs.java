package vn.supra.pdalauncher;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

final class Prefs {
    private static final String NAME = "launcher_prefs";
    private static final String KEY_ALLOWED = "allowed_packages";
    private static final String KEY_ADMIN_UNTIL = "admin_until";

    private Prefs() {}

    static Set<String> getAllowed(Context c) {
        Set<String> s = c.getSharedPreferences(NAME, Context.MODE_PRIVATE).getStringSet(KEY_ALLOWED, null);
        return s == null ? new HashSet<String>() : new HashSet<String>(s);
    }

    static void setAllowed(Context c, Set<String> s) {
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY_ALLOWED, new HashSet<String>(s)).apply();
    }

    static void beginAdminSession(Context c) {
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_ADMIN_UNTIL, System.currentTimeMillis() + 5L * 60L * 1000L).apply();
    }

    static boolean isAdminSession(Context c) {
        long until = c.getSharedPreferences(NAME, Context.MODE_PRIVATE).getLong(KEY_ADMIN_UNTIL, 0L);
        return System.currentTimeMillis() < until;
    }

    static void clearAdminSession(Context c) {
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().remove(KEY_ADMIN_UNTIL).apply();
    }
}
