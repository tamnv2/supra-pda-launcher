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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

final class UpdateManager {
    private static final String RELEASE_API =
            "https://api.github.com/repos/tamnv2/supra-pda-launcher/releases/latest";

    private UpdateManager() {}

    static void check(final Activity activity, final boolean showUpToDate) {
        Toast.makeText(activity, "Đang kiểm tra cập nhật…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(RELEASE_API).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "SUPRA-PDA-Launcher/" + BuildConfig.VERSION_NAME);

                int code = connection.getResponseCode();
                if (code != 200) throw new IllegalStateException("GitHub HTTP " + code);

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder json = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) json.append(line);
                reader.close();

                JSONObject release = new JSONObject(json.toString());
                String tag = release.optString("tag_name", "");
                String latestVersion = normalizeVersion(tag);
                String currentVersion = normalizeVersion(BuildConfig.VERSION_NAME);
                String apkUrl = findApkUrl(release.optJSONArray("assets"));

                if (latestVersion.length() == 0 || apkUrl == null) {
                    throw new IllegalStateException("Release chưa có APK hợp lệ");
                }

                if (compareVersions(latestVersion, currentVersion) > 0) {
                    final String versionToShow = latestVersion;
                    final String urlToDownload = apkUrl;
                    activity.runOnUiThread(() -> showUpdateDialog(activity, versionToShow, urlToDownload));
                } else if (showUpToDate) {
                    activity.runOnUiThread(() -> Toast.makeText(activity,
                            "Đang dùng phiên bản mới nhất: " + BuildConfig.VERSION_NAME,
                            Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                if (showUpToDate) {
                    activity.runOnUiThread(() -> Toast.makeText(activity,
                            "Không thể kiểm tra cập nhật: " + e.getMessage(),
                            Toast.LENGTH_LONG).show());
                }
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "supra-update-check").start();
    }

    private static void showUpdateDialog(final Activity activity, String version, final String apkUrl) {
        new AlertDialog.Builder(activity)
                .setTitle("Có bản cập nhật " + version)
                .setMessage("Tải và cài phiên bản mới của SUPRA PDA Launcher?")
                .setNegativeButton("Để sau", null)
                .setPositiveButton("Cập nhật", (dialog, which) -> startDownload(activity, version, apkUrl))
                .show();
    }

    private static void startDownload(final Activity activity, final String version, final String apkUrl) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent permission = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(permission);
                Toast.makeText(activity,
                        "Hãy cho phép SUPRA PDA cài ứng dụng, sau đó kiểm tra cập nhật lại.",
                        Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(activity, "Không mở được quyền cài đặt ứng dụng.", Toast.LENGTH_LONG).show();
            }
            return;
        }

        Toast.makeText(activity, "Đang tải bản cập nhật…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) throw new IllegalStateException("Không truy cập được thư mục tải xuống");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Không tạo được thư mục tải xuống");

                File apk = new File(dir, "SUPRA-PDA-Launcher-" + version + ".apk");
                connection = (HttpURLConnection) new URL(apkUrl).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("User-Agent", "SUPRA-PDA-Launcher/" + BuildConfig.VERSION_NAME);
                connection.connect();
                if (connection.getResponseCode() != 200) {
                    throw new IllegalStateException("HTTP " + connection.getResponseCode());
                }

                try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream output = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                    output.flush();
                }

                activity.runOnUiThread(() -> launchInstaller(activity, apk));
            } catch (Exception e) {
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        "Tải cập nhật thất bại: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "supra-update-download").start();
    }

    private static void launchInstaller(Activity activity, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    BuildConfig.APPLICATION_ID + ".fileprovider", apk);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(install);
        } catch (Exception e) {
            Toast.makeText(activity, "Không mở được trình cài đặt: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private static String findApkUrl(JSONArray assets) {
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "").toLowerCase(Locale.US);
            if (name.endsWith(".apk")) {
                String url = asset.optString("browser_download_url", "");
                if (url.length() > 0) return url;
            }
        }
        return null;
    }

    private static String normalizeVersion(String version) {
        if (version == null) return "";
        String v = version.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        int dash = v.indexOf('-');
        if (dash >= 0) v = v.substring(0, dash);
        return v;
    }

    private static int compareVersions(String a, String b) {
        String[] aa = a.split("\\.");
        String[] bb = b.split("\\.");
        int length = Math.max(aa.length, bb.length);
        for (int i = 0; i < length; i++) {
            int av = i < aa.length ? parsePart(aa[i]) : 0;
            int bv = i < bb.length ? parsePart(bb[i]) : 0;
            if (av != bv) return av > bv ? 1 : -1;
        }
        return 0;
    }

    private static int parsePart(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
        catch (Exception e) { return 0; }
    }
}
