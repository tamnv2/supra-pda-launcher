package vn.supra.pdalauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

final class UpdateManager {
    private static final String SERVICE_BASE = "https://inventory-beta.supra.cc.cd";
    private static final String MANIFEST_URL = SERVICE_BASE + "/downloads/launcher/manifest";

    // 100 PDA checking once per hour is still very small traffic, while avoiding
    // the old six-hour delay before a mandatory update is noticed.
    private static final long AUTO_CHECK_INTERVAL_MS = 60L * 60L * 1000L;
    private static final long REQUIRED_RECHECK_INTERVAL_MS = 5L * 60L * 1000L;

    private static final AtomicBoolean CHECK_IN_FLIGHT = new AtomicBoolean(false);
    private static final AtomicBoolean DOWNLOAD_IN_FLIGHT = new AtomicBoolean(false);
    private static WeakReference<AlertDialog> activeRequiredDialog =
            new WeakReference<>(null);
    private static String activeRequiredVersion = "";

    private UpdateManager() {}

    static void checkOnLaunch(final Activity activity) {
        if (Prefs.getRequiredUpdateCode(activity) > 0
                && Prefs.getRequiredUpdateCode(activity) <= BuildConfig.VERSION_CODE) {
            Prefs.clearRequiredUpdate(activity);
            dismissRequiredDialog();
        }

        boolean required = Prefs.isRequiredUpdate(activity, BuildConfig.VERSION_CODE);
        if (required) showCachedRequiredDialog(activity);

        long now = System.currentTimeMillis();
        long lastCheck = Prefs.getLastUpdateCheckAt(activity);
        long interval = required ? REQUIRED_RECHECK_INTERVAL_MS : AUTO_CHECK_INTERVAL_MS;
        if (lastCheck > 0L && now - lastCheck >= 0L && now - lastCheck < interval) return;

        checkInternal(activity, false);
    }

    static void check(final Activity activity, final boolean showUpToDate) {
        checkInternal(activity, showUpToDate);
    }

    private static void checkInternal(final Activity activity, final boolean showUpToDate) {
        if (!CHECK_IN_FLIGHT.compareAndSet(false, true)) {
            if (showUpToDate) {
                Toast.makeText(activity, "Đang kiểm tra cập nhật…", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (showUpToDate) {
            Toast.makeText(activity, "Đang kiểm tra cập nhật…", Toast.LENGTH_SHORT).show();
        }
        Prefs.markUpdateCheck(activity, System.currentTimeMillis());

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(MANIFEST_URL).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty(
                        "User-Agent", "SUPRA-PDA-Launcher/" + BuildConfig.VERSION_NAME);

                int code = connection.getResponseCode();
                if (code != 200) throw new IllegalStateException("Dịch vụ cập nhật HTTP " + code);

                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder raw = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null && raw.length() < 32768) {
                    raw.append(line);
                }
                reader.close();

                JSONObject manifest = new JSONObject(raw.toString());
                String latestVersion = normalizeVersion(manifest.optString("version", ""));
                int latestVersionCode = manifest.optInt("version_code", 0);
                String sha256 = manifest.optString("sha256", "")
                        .trim().toLowerCase(Locale.US);
                String apkPath = manifest.optString("apk_path", "").trim();

                if (latestVersion.length() == 0
                        || latestVersionCode <= 0
                        || !sha256.matches("[0-9a-f]{64}")
                        || !apkPath.startsWith("/downloads/launcher/")) {
                    throw new IllegalStateException("Manifest cập nhật không hợp lệ");
                }

                if (latestVersionCode > BuildConfig.VERSION_CODE) {
                    final String versionToShow = latestVersion;
                    final String urlToDownload = SERVICE_BASE + apkPath;
                    final String expectedSha256 = sha256;

                    // Once a newer release is observed, persist the gate locally.
                    // It stays enforced across app restarts and installer cancellation.
                    Prefs.markRequiredUpdate(
                            activity,
                            versionToShow,
                            latestVersionCode,
                            urlToDownload,
                            expectedSha256);

                    activity.runOnUiThread(() ->
                            showRequiredUpdateDialog(
                                    activity,
                                    versionToShow,
                                    urlToDownload,
                                    expectedSha256));
                } else {
                    // This also releases the gate if the server/channel was rolled
                    // back to a known-good version after a bad release.
                    Prefs.clearRequiredUpdate(activity);
                    activity.runOnUiThread(() -> {
                        dismissRequiredDialog();
                        if (showUpToDate) {
                            Toast.makeText(
                                    activity,
                                    "Đang dùng phiên bản mới nhất: " + BuildConfig.VERSION_NAME,
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            } catch (Exception e) {
                if (showUpToDate) {
                    activity.runOnUiThread(() -> Toast.makeText(
                            activity,
                            "Không thể kiểm tra cập nhật: " + e.getMessage(),
                            Toast.LENGTH_LONG).show());
                }
                // Fail open only when no mandatory update has previously been observed.
                // If a required update is already cached, its blocking dialog remains.
            } finally {
                if (connection != null) connection.disconnect();
                CHECK_IN_FLIGHT.set(false);
            }
        }, "supra-update-check").start();
    }

    private static void showCachedRequiredDialog(Activity activity) {
        String version = normalizeVersion(Prefs.getRequiredUpdateVersion(activity));
        String apkUrl = Prefs.getRequiredUpdateUrl(activity);
        String sha256 = Prefs.getRequiredUpdateSha256(activity)
                .trim().toLowerCase(Locale.US);

        if (version.length() == 0
                || !apkUrl.startsWith(SERVICE_BASE + "/downloads/launcher/")
                || !sha256.matches("[0-9a-f]{64}")) {
            Prefs.clearRequiredUpdate(activity);
            return;
        }
        showRequiredUpdateDialog(activity, version, apkUrl, sha256);
    }

    private static void showRequiredUpdateDialog(
            final Activity activity,
            final String version,
            final String apkUrl,
            final String expectedSha256) {
        if (activity.isFinishing()) return;

        AlertDialog existing = activeRequiredDialog.get();
        if (existing != null && existing.isShowing()) {
            if (version.equals(activeRequiredVersion)) return;
            try {
                existing.dismiss();
            } catch (Exception ignored) { }
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Bắt buộc cập nhật " + version)
                .setMessage(
                        "Đã có phiên bản Launcher mới. Cần cập nhật để tiếp tục sử dụng PDA.\n\n"
                        + "Nếu bản phát hành được tạm dừng do lỗi, bấm “Kiểm tra lại” "
                        + "để nhận trạng thái mới từ hệ thống.")
                .setPositiveButton("Cập nhật", null)
                .setNeutralButton("Kiểm tra lại", null)
                .setCancelable(false)
                .create();

        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                    startDownload(activity, version, apkUrl, expectedSha256));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    checkInternal(activity, false));
        });
        dialog.setOnDismissListener(ignored -> {
            AlertDialog active = activeRequiredDialog.get();
            if (active == dialog) {
                activeRequiredDialog = new WeakReference<>(null);
                activeRequiredVersion = "";
            }
        });

        activeRequiredVersion = version;
        activeRequiredDialog = new WeakReference<>(dialog);
        dialog.show();
    }

    private static void dismissRequiredDialog() {
        AlertDialog dialog = activeRequiredDialog.get();
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (Exception ignored) { }
        }
        activeRequiredDialog = new WeakReference<>(null);
        activeRequiredVersion = "";
    }

    private static void startDownload(
            final Activity activity,
            final String version,
            final String apkUrl,
            final String expectedSha256) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            try {
                Prefs.beginUpdateSession(activity);
                Intent permission = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(permission);
                Toast.makeText(
                        activity,
                        "Hãy cho phép SUPRA PDA cài ứng dụng rồi quay lại bấm Cập nhật.",
                        Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(
                        activity,
                        "Không mở được quyền cài đặt ứng dụng.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (!DOWNLOAD_IN_FLIGHT.compareAndSet(false, true)) {
            Toast.makeText(activity, "Bản cập nhật đang được tải…", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(activity, "Đang tải bản cập nhật…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) {
                    throw new IllegalStateException("Không truy cập được thư mục tải xuống");
                }
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("Không tạo được thư mục tải xuống");
                }

                File apk = new File(dir, "SUPRA-PDA-Launcher-" + version + ".apk");
                connection = (HttpURLConnection) new URL(apkUrl).openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(45000);
                connection.setRequestProperty(
                        "User-Agent", "SUPRA-PDA-Launcher/" + BuildConfig.VERSION_NAME);
                connection.connect();
                if (connection.getResponseCode() != 200) {
                    throw new IllegalStateException("HTTP " + connection.getResponseCode());
                }

                try (BufferedInputStream input =
                             new BufferedInputStream(connection.getInputStream());
                     FileOutputStream output = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                    output.flush();
                }

                String actualSha256 = sha256(apk);
                if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
                    //noinspection ResultOfMethodCallIgnored
                    apk.delete();
                    throw new IllegalStateException(
                            "SHA-256 không khớp, đã hủy file cập nhật");
                }

                DOWNLOAD_IN_FLIGHT.set(false);
                activity.runOnUiThread(() -> launchInstaller(activity, apk));
            } catch (Exception e) {
                DOWNLOAD_IN_FLIGHT.set(false);
                activity.runOnUiThread(() -> Toast.makeText(
                        activity,
                        "Tải cập nhật thất bại: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "supra-update-download").start();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        byte[] bytes = digest.digest();
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            hex.append(String.format(Locale.US, "%02x", item & 0xff));
        }
        return hex.toString();
    }

    private static void launchInstaller(Activity activity, File apk) {
        try {
            Prefs.beginUpdateSession(activity);
            Uri uri = FileProvider.getUriForFile(
                    activity,
                    BuildConfig.APPLICATION_ID + ".fileprovider",
                    apk);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(install);
        } catch (Exception e) {
            Toast.makeText(
                    activity,
                    "Không mở được trình cài đặt: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private static String normalizeVersion(String version) {
        if (version == null) return "";
        String v = version.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        int dash = v.indexOf('-');
        if (dash >= 0) v = v.substring(0, dash);
        return v.matches("\\d+\\.\\d+\\.\\d+") ? v : "";
    }
}
