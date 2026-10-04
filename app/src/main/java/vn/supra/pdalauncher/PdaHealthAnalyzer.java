package vn.supra.pdalauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

final class PdaHealthAnalyzer {
    private static final int MAX_LIST_ITEMS = 6;

    private PdaHealthAnalyzer() { }

    static String buildReport(Context context) {
        PackageManager pm = context.getPackageManager();
        Set<String> allowed = Prefs.getAllowed(context);

        List<ApplicationInfo> outsideBusinessApps = new ArrayList<ApplicationInfo>();
        List<ApplicationInfo> backgroundCapableApps = new ArrayList<ApplicationInfo>();

        try {
            List<ApplicationInfo> installed =
                    pm.getInstalledApplications(PackageManager.GET_META_DATA);
            if (installed != null) {
                for (ApplicationInfo info : installed) {
                    if (info == null || info.packageName == null) continue;
                    if (context.getPackageName().equals(info.packageName)) continue;
                    if (isSystemOrUpdatedSystem(info)) continue;

                    boolean allowedApp = allowed.contains(info.packageName);
                    boolean launchable = hasLaunchActivity(pm, info.packageName);

                    if (launchable && !allowedApp) {
                        outsideBusinessApps.add(info);
                    }

                    if (!allowedApp && hasBackgroundCapability(pm, info.packageName)) {
                        backgroundCapableApps.add(info);
                    }
                }
            }
        } catch (Exception ignored) { }

        Comparator<ApplicationInfo> byLabel = new Comparator<ApplicationInfo>() {
            @Override public int compare(ApplicationInfo a, ApplicationInfo b) {
                return appLabel(pm, a).compareToIgnoreCase(appLabel(pm, b));
            }
        };
        Collections.sort(outsideBusinessApps, byLabel);
        Collections.sort(backgroundCapableApps, byLabel);

        StringBuilder out = new StringBuilder();
        out.append("Quét theo yêu cầu, không chạy nền liên tục.\n\n");

        if (outsideBusinessApps.isEmpty()) {
            out.append("✓ Không phát hiện app cài thêm có biểu tượng nằm ngoài danh sách nghiệp vụ.\n");
        } else {
            out.append("! App cài thêm ngoài danh sách nghiệp vụ: ")
                    .append(outsideBusinessApps.size()).append("\n");
            appendApps(out, pm, outsideBusinessApps);
            out.append("  → Nên gỡ nếu không phục vụ công việc.\n");
        }

        out.append("\n");
        if (backgroundCapableApps.isEmpty()) {
            out.append("✓ Không phát hiện app cài thêm ngoài whitelist có quyền nền đáng chú ý.\n");
        } else {
            out.append("! App ngoài whitelist có khả năng chạy nền/khởi động/cài app: ")
                    .append(backgroundCapableApps.size()).append("\n");
            appendApps(out, pm, backgroundCapableApps);
            out.append("  → Chỉ kiểm tra/cân nhắc gỡ; Launcher không tự tắt để tránh ảnh hưởng dịch vụ máy quét.\n");
        }

        PowerManager power =
                (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power != null) {
            out.append("\n");
            if (power.isPowerSaveMode()) {
                out.append("! Tiết kiệm pin toàn máy: ĐANG BẬT. Nên tắt trong ca để tránh bóp SFT/SFT3/Báo hàng.\n");
            } else {
                out.append("✓ Tiết kiệm pin toàn máy: đang tắt, phù hợp cho ca làm việc.\n");
            }

            try {
                if (power.isIgnoringBatteryOptimizations(context.getPackageName())) {
                    out.append("! Launcher PDA đang được miễn tối ưu pin. Không cần thiết; có thể để Android tối ưu Launcher.\n");
                } else {
                    out.append("✓ Launcher PDA đang để Android tối ưu pin.\n");
                }
            } catch (Exception ignored) { }
        }

        appendDisplayAdvice(context, out);

        out.append("\nNguyên tắc an toàn: không force-stop hàng loạt, không vô hiệu hóa app hệ thống/vendor scanner, không thay đổi quyền nền của SFT/SFT3/Báo hàng tự động.");
        return out.toString();
    }

    private static void appendDisplayAdvice(Context context, StringBuilder out) {
        try {
            int mode = Settings.System.getInt(
                    context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);

            if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                out.append("✓ Độ sáng: tự động.\n");
            } else {
                int brightness = Settings.System.getInt(
                        context.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS,
                        128);
                int percent = Math.round(brightness * 100f / 255f);
                if (percent > 55) {
                    out.append("! Độ sáng hiện khoảng ").append(percent)
                            .append("%. Có thể giảm về khoảng 40–50% nếu vẫn đủ nhìn trong kho.\n");
                } else {
                    out.append("✓ Độ sáng hiện khoảng ").append(percent).append("%.\n");
                }
            }
        } catch (Exception ignored) { }

        try {
            int timeout = Settings.System.getInt(
                    context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    120000);
            if (timeout > 180000) {
                out.append("! Tắt màn hình sau ").append(formatDuration(timeout))
                        .append(". Có thể giảm còn 1–2 phút nếu không ảnh hưởng thao tác pick.\n");
            } else {
                out.append("✓ Thời gian tắt màn hình: ").append(formatDuration(timeout)).append(".\n");
            }
        } catch (Exception ignored) { }
    }

    private static boolean isSystemOrUpdatedSystem(ApplicationInfo info) {
        return (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                || (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    private static boolean hasLaunchActivity(PackageManager pm, String packageName) {
        try {
            Intent launch = pm.getLaunchIntentForPackage(packageName);
            if (launch == null) return false;
            return pm.resolveActivity(launch, PackageManager.MATCH_DEFAULT_ONLY) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasBackgroundCapability(PackageManager pm, String packageName) {
        try {
            PackageInfo info = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
            String[] permissions = info.requestedPermissions;
            if (permissions == null) return false;

            for (String permission : permissions) {
                if ("android.permission.RECEIVE_BOOT_COMPLETED".equals(permission)
                        || "android.permission.WAKE_LOCK".equals(permission)
                        || "android.permission.FOREGROUND_SERVICE".equals(permission)
                        || "android.permission.SYSTEM_ALERT_WINDOW".equals(permission)
                        || "android.permission.REQUEST_INSTALL_PACKAGES".equals(permission)) {
                    return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static void appendApps(
            StringBuilder out,
            PackageManager pm,
            List<ApplicationInfo> apps) {
        int count = Math.min(MAX_LIST_ITEMS, apps.size());
        for (int i = 0; i < count; i++) {
            ApplicationInfo info = apps.get(i);
            out.append("  • ").append(appLabel(pm, info))
                    .append(" (").append(info.packageName).append(")\n");
        }
        if (apps.size() > count) {
            out.append("  • +").append(apps.size() - count).append(" app khác\n");
        }
    }

    private static String appLabel(PackageManager pm, ApplicationInfo info) {
        try {
            CharSequence label = info.loadLabel(pm);
            if (label != null && label.length() > 0) return label.toString();
        } catch (Exception ignored) { }
        return info.packageName == null ? "Không rõ" : info.packageName;
    }

    private static String formatDuration(int millis) {
        if (millis <= 0) return "không xác định";
        int seconds = millis / 1000;
        if (seconds < 60) return seconds + " giây";
        int minutes = seconds / 60;
        if (minutes < 60) return minutes + " phút";
        int hours = minutes / 60;
        return hours + " giờ";
    }
}
