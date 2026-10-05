package vn.supra.pdalauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GuardService extends AccessibilityService {
    private String lastMeaningfulPackage;
    private Set<String> allowedPackages = Collections.emptySet();
    private Set<String> imePackages = Collections.emptySet();
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        reloadAllowedPackages();
        reloadInputMethodPackages();

        preferenceListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override public void onSharedPreferenceChanged(
                    SharedPreferences sharedPreferences, String key) {
                if (Prefs.KEY_ALLOWED.equals(key)) reloadAllowedPackages();
            }
        };
        Prefs.preferences(this).registerOnSharedPreferenceChangeListener(preferenceListener);
    }

    @Override public void onDestroy() {
        if (preferenceListener != null) {
            try {
                Prefs.preferences(this)
                        .unregisterOnSharedPreferenceChangeListener(preferenceListener);
            } catch (Exception ignored) { }
            preferenceListener = null;
        }
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        String className = event.getClassName() == null ? "" : event.getClassName().toString();

        if (Prefs.isGuardPaused(this)) {
            return;
        }

        if (pkg.equals(getPackageName())) {
            lastMeaningfulPackage = pkg;
            Prefs.clearUpdateSession(this);
            return;
        }

        if (isAlwaysAllowedSystem(pkg)) return;

        boolean allowedApp = allowedPackages.contains(pkg);
        if (allowedApp) {
            lastMeaningfulPackage = pkg;
            Prefs.clearUpdateSession(this);
            return;
        }

        if (isInstallerPackage(pkg)) {
            if (Prefs.isUpdateSession(this) || isAllowedUpdateOrigin()) {
                Prefs.beginUpdateSession(this);
                lastMeaningfulPackage = pkg;
                return;
            }
        }

        if ("com.android.settings".equals(pkg) && isExternalSourcesScreen(className)) {
            if (Prefs.isUpdateSession(this) || isAllowedUpdateOrigin()) {
                Prefs.beginUpdateSession(this);
                lastMeaningfulPackage = pkg;
                return;
            }
        }

        if (Prefs.isUninstallPackageAllowed(this, pkg)) return;
        if (Prefs.isAdminSession(this) && isAdminSystemPackage(pkg)) return;
        if (Prefs.isTimeFixSession(this) && "com.android.settings".equals(pkg)) return;

        Prefs.clearUninstallSession(this);
        Prefs.clearUpdateSession(this);
        lastMeaningfulPackage = pkg;
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    @Override public void onInterrupt() { }

    private boolean isAlwaysAllowedSystem(String pkg) {
        if ("com.android.systemui".equals(pkg)) return true;
        if ("com.android.permissioncontroller".equals(pkg)) return true;
        if ("com.google.android.permissioncontroller".equals(pkg)) return true;
        if ("com.android.documentsui".equals(pkg)) return true;
        if ("com.google.android.documentsui".equals(pkg)) return true;
        return imePackages.contains(pkg);
    }

    private boolean isAllowedUpdateOrigin() {
        return lastMeaningfulPackage != null
                && allowedPackages.contains(lastMeaningfulPackage);
    }

    private void reloadAllowedPackages() {
        allowedPackages = Collections.unmodifiableSet(
                new HashSet<String>(Prefs.getAllowed(this)));
    }

    private void reloadInputMethodPackages() {
        Set<String> packages = new HashSet<String>();
        try {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                List<InputMethodInfo> list = imm.getInputMethodList();
                if (list != null) {
                    for (InputMethodInfo info : list) {
                        if (info != null && info.getPackageName() != null) {
                            packages.add(info.getPackageName());
                        }
                    }
                }
            }
        } catch (Exception ignored) { }
        imePackages = Collections.unmodifiableSet(packages);
    }

    private boolean isInstallerPackage(String pkg) {
        return "com.android.packageinstaller".equals(pkg)
                || "com.google.android.packageinstaller".equals(pkg);
    }

    private boolean isExternalSourcesScreen(String className) {
        String name = className == null ? "" : className.toLowerCase();
        return name.contains("external")
                || name.contains("unknownsource")
                || name.contains("unknown_source")
                || name.contains("installunknown");
    }

    private boolean isAdminSystemPackage(String pkg) {
        return "com.android.settings".equals(pkg)
                || "com.android.packageinstaller".equals(pkg)
                || "com.google.android.packageinstaller".equals(pkg);
    }
}
