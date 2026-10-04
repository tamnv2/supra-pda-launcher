package vn.supra.pdalauncher;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

final class Prefs {
    private static final String NAME = "launcher_prefs";
    private static final String KEY_ALLOWED = "allowed_packages";
    private static final String KEY_ADMIN_UNTIL = "admin_until";
    private static final String KEY_SHOW_BATTERY = "show_battery";
    private static final String KEY_LOW_BATTERY_ALERT = "low_battery_alert";
    private static final String KEY_TIME_FIX_UNTIL = "time_fix_until";
    private static final String KEY_UPDATE_UNTIL = "update_until";
    private static final String KEY_UNINSTALL_UNTIL = "uninstall_until";
    private static final String KEY_UNINSTALL_PACKAGE = "uninstall_package";

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

    static boolean isShowBattery(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_BATTERY, true);
    }

    static void setShowBattery(Context c, boolean enabled) {
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_SHOW_BATTERY, enabled).apply();
    }

    static boolean isLowBatteryAlertEnabled(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(KEY_LOW_BATTERY_ALERT, true);
    }

    static void setLowBatteryAlertEnabled(Context c, boolean enabled) {
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_LOW_BATTERY_ALERT, enabled).apply();
    }

    static void beginTimeFixSession(Context c) {
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_TIME_FIX_UNTIL, System.currentTimeMillis() + 90L * 1000L).apply();
    }

    static boolean isTimeFixSession(Context c) {
        long until = c.getSharedPreferences(NAME, Context.MODE_PRIVATE).getLong(KEY_TIME_FIX_UNTIL, 0L);
        return System.currentTimeMillis() < until;
    }

    static void clearTimeFixSession(Context c) {
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().remove(KEY_TIME_FIX_UNTIL).apply();
    }

    static void beginUpdateSession(Context c) {
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_UPDATE_UNTIL, System.currentTimeMillis() + 3L * 60L * 1000L).apply();
    }

    static boolean isUpdateSession(Context c) {
        long until = c.getSharedPreferences(NAME, Context.MODE_PRIVATE).getLong(KEY_UPDATE_UNTIL, 0L);
        long now = System.currentTimeMillis();
        long remaining = until - now;
        return remaining > 0L && remaining <= 3L * 60L * 1000L;
    }

    static void clearUpdateSession(Context c) {
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().remove(KEY_UPDATE_UNTIL).apply();
    }

    static void beginUninstallSession(Context c, String packageName) {
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_UNINSTALL_UNTIL, System.currentTimeMillis() + 90L * 1000L)
                .putString(KEY_UNINSTALL_PACKAGE, packageName == null ? "" : packageName)
                .apply();
    }

    static boolean isUninstallPackageAllowed(Context c, String packageName) {
        long until = c.getSharedPreferences(NAME, Context.MODE_PRIVATE).getLong(KEY_UNINSTALL_UNTIL, 0L);
        long now = System.currentTimeMillis();
        String allowed = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getString(KEY_UNINSTALL_PACKAGE, "");
        return until > now
                && until - now <= 90L * 1000L
                && packageName != null
                && packageName.equals(allowed);
    }

    static void clearUninstallSession(Context c) {
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_UNINSTALL_UNTIL)
                .remove(KEY_UNINSTALL_PACKAGE)
                .apply();
    }
}
