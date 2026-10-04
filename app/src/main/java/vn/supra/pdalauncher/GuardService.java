package vn.supra.pdalauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import java.util.List;

public class GuardService extends AccessibilityService {
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (pkg.equals(getPackageName())) return;
        if (isAlwaysAllowedSystem(pkg)) return;
        if (Prefs.getAllowed(this).contains(pkg)) return;
        if (Prefs.isAdminSession(this) && isAdminSystemPackage(pkg)) return;
        if (Prefs.isTimeFixSession(this) && "com.android.settings".equals(pkg)) return;
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

    private boolean isAdminSystemPackage(String pkg) {
        return "com.android.settings".equals(pkg)
                || "com.android.packageinstaller".equals(pkg)
                || "com.google.android.packageinstaller".equals(pkg);
    }
}
