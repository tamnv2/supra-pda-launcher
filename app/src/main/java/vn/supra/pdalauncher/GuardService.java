package vn.supra.pdalauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import java.util.List;

public class GuardService extends AccessibilityService {
    private String lastMeaningfulPackage;
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        String className = event.getClassName() == null ? "" : event.getClassName().toString();

        if (pkg.equals(getPackageName())) {
            lastMeaningfulPackage = pkg;
            Prefs.clearUpdateSession(this);
            return;
        }

        if (isAlwaysAllowedSystem(pkg)) return;

        boolean allowedApp = Prefs.getAllowed(this).contains(pkg);
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
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            List<InputMethodInfo> list = imm.getInputMethodList();
            for (InputMethodInfo info : list) if (info != null && pkg.equals(info.getPackageName())) return true;
        } catch (Exception ignored) { }
        return false;
    }

    private boolean isAllowedUpdateOrigin() {
        return lastMeaningfulPackage != null
                && Prefs.getAllowed(this).contains(lastMeaningfulPackage);
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
