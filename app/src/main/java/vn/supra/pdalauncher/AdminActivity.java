package vn.supra.pdalauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AdminActivity extends Activity {
    private static final int REQUEST_UNINSTALL_APP = 3210;
    private Set<String> allowed;
    private TextView statusLauncher;
    private TextView statusGuard;
    private LinearLayout uninstallList;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!Prefs.isAdminSession(this)) { finish(); return; }
        allowed = Prefs.getAllowed(this);
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshUninstallableApps();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_UNINSTALL_APP) {
            Prefs.clearUninstallSession(this);
            refreshUninstallableApps();
            Toast.makeText(this,
                    resultCode == Activity.RESULT_OK ? "Đã gỡ cài đặt." : "Đã đóng trình gỡ cài đặt.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onBackPressed() {
        Prefs.clearAdminSession(this);
        super.onBackPressed();
    }

    private void buildUi() {
        final boolean compact = isCompactWidth();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setBackgroundColor(0xFFF5F7FA);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = dp(compact ? 12 : 16);
        root.setPadding(side, dp(12), side, dp(22));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(14));

        ImageView logo = new ImageView(this);
        logo.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(compact ? 44 : 48), dp(compact ? 44 : 48));
        logoLp.setMargins(0, 0, dp(12), 0);
        header.addView(logo, logoLp);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("Cài đặt quản trị");
        title.setTextSize(compact ? 19f : 21f);
        title.setTextColor(0xFF152238);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView subtitle = new TextView(this);
        subtitle.setText("v" + BuildConfig.VERSION_NAME);
        subtitle.setTextSize(compact ? 11f : 12f);
        subtitle.setTextColor(0xFF718096);
        titleBox.addView(title);
        titleBox.addView(subtitle);
        header.addView(titleBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageView close = new ImageView(this);
        close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        close.setPadding(dp(12), dp(12), dp(12), dp(12));
        close.setBackground(cardBackground(0xFFFFFFFF, 14f));
        close.setContentDescription("Đóng quản trị");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.clearAdminSession(AdminActivity.this);
                finish();
            }
        });
        header.addView(close, new LinearLayout.LayoutParams(dp(compact ? 42 : 46), dp(compact ? 42 : 46)));
        root.addView(header);

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setBackground(cardBackground(0xFFFFFFFF, 16f));
        statusLauncher = statusChip("Màn hình chính");
        statusGuard = statusChip("Chống lách");
        statusCard.addView(statusLauncher, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        statusCard.addView(statusGuard, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.setMargins(0, 0, 0, dp(18));
        root.addView(statusCard, statusLp);

        root.addView(section("Ứng dụng hiển thị"));

        LinearLayout appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        List<ResolveInfo> apps = queryLauncherApps();
        Set<String> seen = new HashSet<String>();
        int count = 0;
        for (final ResolveInfo r : apps) {
            if (r.activityInfo == null) continue;
            final String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            View row = appRow(r, pkg);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, 0, 0, dp(8));
            appList.addView(row, rowLp);
            count++;
        }
        if (count == 0) {
            TextView none = new TextView(this);
            none.setText("Không tìm thấy ứng dụng có thể mở trên PDA.");
            none.setTextColor(0xFF718096);
            none.setTextSize(13f);
            none.setPadding(dp(14), dp(18), dp(14), dp(18));
            none.setBackground(cardBackground(0xFFFFFFFF, 14f));
            appList.addView(none);
        }
        root.addView(appList);

        root.addView(section("Dọn dẹp ứng dụng"));
        TextView cleanupHint = new TextView(this);
        cleanupHint.setText("Chỉ hiển thị ứng dụng người dùng cài thêm. Launcher PDA và ứng dụng hệ thống được bảo vệ.");
        cleanupHint.setTextSize(compact ? 11.5f : 12.5f);
        cleanupHint.setTextColor(0xFF64748B);
        cleanupHint.setPadding(dp(2), 0, dp(2), dp(8));
        root.addView(cleanupHint);

        uninstallList = new LinearLayout(this);
        uninstallList.setOrientation(LinearLayout.VERTICAL);
        root.addView(uninstallList);
        refreshUninstallableApps();

        root.addView(section("Bảo mật"));
        root.addView(actionRow(android.R.drawable.ic_lock_lock,
                "Đổi mật khẩu quản trị",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showChangePassword(); }
                }));

        root.addView(section("Cập nhật"));
        root.addView(actionRow(android.R.drawable.ic_popup_sync,
                "Kiểm tra cập nhật",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        UpdateManager.check(AdminActivity.this, true);
                    }
                }));

        root.addView(section("Cài đặt nhanh"));

        root.addView(actionRow(android.R.drawable.ic_menu_manage,
                "Wi-Fi",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openSystemSettings(Settings.ACTION_WIFI_SETTINGS);
                    }
                }));

        root.addView(actionRow(android.R.drawable.ic_menu_day,
                "Độ sáng màn hình",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openSystemSettings(Settings.ACTION_DISPLAY_SETTINGS);
                    }
                }));

        root.addView(actionRow(android.R.drawable.ic_menu_rotate,
                "Tự động xoay",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openSystemSettings(Settings.ACTION_DISPLAY_SETTINGS);
                    }
                }));

        root.addView(actionRow(android.R.drawable.ic_lock_idle_low_battery,
                "Tiết kiệm pin",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openSystemSettings("android.settings.BATTERY_SAVER_SETTINGS");
                    }
                }));

        root.addView(actionRow(android.R.drawable.ic_menu_mylocation,
                "Vị trí",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openSystemSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    }
                }));

        root.addView(toggleRow("Hiển thị % pin", Prefs.isShowBattery(AdminActivity.this),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        Prefs.setShowBattery(AdminActivity.this, isChecked);
                    }
                }));

        root.addView(toggleRow("Cảnh báo pin yếu", Prefs.isLowBatteryAlertEnabled(AdminActivity.this),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        Prefs.setLowBatteryAlertEnabled(AdminActivity.this, isChecked);
                        if (!isChecked) BatteryAlertReceiver.cancel(AdminActivity.this);
                    }
                }));

        root.addView(section("Thiết lập PDA"));
        root.addView(actionRow(android.R.drawable.ic_menu_view,
                "Chọn Launcher PDA làm màn hình chính",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Prefs.beginAdminSession(AdminActivity.this);
                        try { startActivity(new Intent("android.settings.HOME_SETTINGS")); }
                        catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                    }
                }));

        root.addView(actionRow(android.R.drawable.ic_secure,
                "Bật / kiểm tra chống lách",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Prefs.beginAdminSession(AdminActivity.this);
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }
                }));

        root.addView(actionRow(android.R.drawable.ic_menu_preferences,
                "Mở Cài đặt Android",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Prefs.beginAdminSession(AdminActivity.this);
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    }
                }));

        setContentView(scroll);
    }

    private View appRow(final ResolveInfo info, final String pkg) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(isCompactWidth() ? 10 : 13), dp(9), dp(isCompactWidth() ? 9 : 11), dp(9));
        row.setBackground(cardBackground(0xFFFFFFFF, 14f));

        ImageView icon = new ImageView(this);
        Drawable d = info.loadIcon(getPackageManager());
        icon.setImageDrawable(d);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(isCompactWidth() ? 40 : 44), dp(isCompactWidth() ? 40 : 44));
        iconLp.setMargins(0, 0, dp(12), 0);
        row.addView(icon, iconLp);

        TextView name = new TextView(this);
        name.setText(info.loadLabel(getPackageManager()));
        name.setTextSize(isCompactWidth() ? 13f : 14f);
        name.setTextColor(0xFF263445);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setIncludeFontPadding(false);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final Switch toggle = new Switch(this);
        toggle.setChecked(allowed.contains(pkg));
        toggle.setContentDescription("Cho phép hiển thị " + info.loadLabel(getPackageManager()));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) allowed.add(pkg); else allowed.remove(pkg);
                Prefs.setAllowed(AdminActivity.this, allowed);
                Toast.makeText(AdminActivity.this,
                        isChecked ? "Đã cho phép hiển thị" : "Đã ẩn ứng dụng",
                        Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(toggle, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void refreshUninstallableApps() {
        if (uninstallList == null) return;
        uninstallList.removeAllViews();

        List<ApplicationInfo> apps = new ArrayList<ApplicationInfo>();
        try {
            for (ApplicationInfo info : getPackageManager().getInstalledApplications(PackageManager.GET_META_DATA)) {
                if (info == null) continue;
                if (getPackageName().equals(info.packageName)) continue;
                if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                apps.add(info);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Không đọc được danh sách ứng dụng.", Toast.LENGTH_SHORT).show();
        }

        Collections.sort(apps, new Comparator<ApplicationInfo>() {
            @Override public int compare(ApplicationInfo a, ApplicationInfo b) {
                return String.valueOf(a.loadLabel(getPackageManager()))
                        .compareToIgnoreCase(String.valueOf(b.loadLabel(getPackageManager())));
            }
        });

        if (apps.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("Không có ứng dụng cài thêm để gỡ.");
            none.setTextSize(isCompactWidth() ? 12f : 13f);
            none.setTextColor(0xFF718096);
            none.setPadding(dp(14), dp(16), dp(14), dp(16));
            none.setBackground(cardBackground(0xFFFFFFFF, 14f));
            uninstallList.addView(none);
            return;
        }

        for (final ApplicationInfo info : apps) {
            View row = uninstallRow(info);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, 0, 0, dp(8));
            uninstallList.addView(row, rowLp);
        }
    }

    private View uninstallRow(final ApplicationInfo info) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(isCompactWidth() ? 10 : 13), dp(9), dp(isCompactWidth() ? 9 : 11), dp(9));
        row.setBackground(cardBackground(0xFFFFFFFF, 14f));

        ImageView icon = new ImageView(this);
        try { icon.setImageDrawable(info.loadIcon(getPackageManager())); }
        catch (Exception ignored) { icon.setImageResource(android.R.drawable.sym_def_app_icon); }
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                dp(isCompactWidth() ? 38 : 42), dp(isCompactWidth() ? 38 : 42));
        iconLp.setMargins(0, 0, dp(12), 0);
        row.addView(icon, iconLp);

        TextView name = new TextView(this);
        name.setText(info.loadLabel(getPackageManager()));
        name.setTextSize(isCompactWidth() ? 12.8f : 13.8f);
        name.setTextColor(0xFF263445);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setIncludeFontPadding(false);
        row.addView(name, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button uninstall = new Button(this);
        uninstall.setText("Gỡ");
        uninstall.setAllCaps(false);
        uninstall.setTextSize(isCompactWidth() ? 11.5f : 12.5f);
        uninstall.setMinWidth(0);
        uninstall.setMinimumWidth(0);
        uninstall.setPadding(dp(12), 0, dp(12), 0);
        uninstall.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmUninstall(info);
            }
        });
        row.addView(uninstall, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(isCompactWidth() ? 40 : 42)));
        return row;
    }

    private void confirmUninstall(final ApplicationInfo info) {
        final CharSequence label = info.loadLabel(getPackageManager());
        new AlertDialog.Builder(this)
                .setTitle("Gỡ cài đặt ứng dụng")
                .setMessage("Gỡ \"" + label + "\" khỏi PDA?")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Gỡ", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        launchUninstall(info.packageName);
                    }
                })
                .show();
    }

    private void launchUninstall(String packageName) {
        Prefs.beginAdminSession(this);
        Prefs.clearUninstallSession(this);

        Intent uninstall = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
        uninstall.setData(Uri.parse("package:" + packageName));
        uninstall.putExtra(Intent.EXTRA_RETURN_RESULT, true);

        ResolveInfo handler = getPackageManager().resolveActivity(
                uninstall, PackageManager.MATCH_DEFAULT_ONLY);

        if (handler == null || handler.activityInfo == null) {
            uninstall = new Intent(Intent.ACTION_DELETE);
            uninstall.setData(Uri.parse("package:" + packageName));
            handler = getPackageManager().resolveActivity(
                    uninstall, PackageManager.MATCH_DEFAULT_ONLY);
        }

        if (handler == null || handler.activityInfo == null) {
            Toast.makeText(this,
                    "PDA không tìm thấy trình gỡ cài đặt.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Prefs.beginUninstallSession(this, handler.activityInfo.packageName);

        try {
            startActivityForResult(uninstall, REQUEST_UNINSTALL_APP);
        } catch (Exception e) {
            Prefs.clearUninstallSession(this);
            Toast.makeText(this,
                    "Không mở được trình gỡ cài đặt: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private View section(String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(2), dp(20), dp(2), dp(10));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(isCompactWidth() ? 15.5f : 16.5f);
        t.setTextColor(0xFF203040);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        box.addView(t);
        return box;
    }

    private View actionRow(int iconRes, String title, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(isCompactWidth() ? 10 : 13), dp(11), dp(isCompactWidth() ? 10 : 13), dp(11));
        row.setBackground(cardBackground(0xFFFFFFFF, 14f));
        row.setOnClickListener(listener);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(rowLp);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setPadding(dp(5), dp(5), dp(5), dp(5));
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setColor(0xFFEAF1FF);
        iconBg.setCornerRadius(dp(12));
        icon.setBackground(iconBg);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(isCompactWidth() ? 38 : 42), dp(isCompactWidth() ? 38 : 42));
        iconLp.setMargins(0, 0, dp(12), 0);
        row.addView(icon, iconLp);

        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(isCompactWidth() ? 13f : 14f);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setTextColor(0xFF263445);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        textBox.addView(t);
        row.addView(textBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(30f);
        arrow.setTextColor(0xFFA1ACB8);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(44)));
        return row;
    }

    private View toggleRow(String title, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(isCompactWidth() ? 10 : 13), dp(11), dp(isCompactWidth() ? 9 : 11), dp(11));
        row.setBackground(cardBackground(0xFFFFFFFF, 14f));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(rowLp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(isCompactWidth() ? 13f : 14f);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setTextColor(0xFF263445);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);
        row.addView(toggle);
        return row;
    }

    private void openSystemSettings(String action) {
        Prefs.beginAdminSession(this);
        try {
            Intent intent = new Intent(action);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            } else {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private TextView statusChip(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12.5f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(8), dp(8), dp(8));
        return t;
    }

    private void refreshStatus() {
        if (statusLauncher != null) {
            boolean on = isDefaultHome();
            statusLauncher.setText("Màn hình chính\n" + (on ? "ĐÃ CHỌN" : "CHƯA CHỌN"));
            statusLauncher.setTextColor(on ? 0xFF16784A : 0xFFC46A1A);
        }
        if (statusGuard != null) {
            boolean on = isGuardEnabled();
            statusGuard.setText("Chống lách\n" + (on ? "ĐÃ BẬT" : "CHƯA BẬT"));
            statusGuard.setTextColor(on ? 0xFF16784A : 0xFFC46A1A);
        }
    }

    private void showChangePassword() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), 0, dp(18), 0);
        final EditText p1 = passwordInput("Mật khẩu mới (tối thiểu 8 ký tự)");
        final EditText p2 = passwordInput("Nhập lại mật khẩu mới");
        box.addView(p1); box.addView(p2);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Đổi mật khẩu quản trị")
                .setView(box)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Lưu", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String a = p1.getText().toString();
                        String b = p2.getText().toString();
                        if (a.length() < 8) { p1.setError("Tối thiểu 8 ký tự"); return; }
                        if (!a.equals(b)) { p2.setError("Hai mật khẩu không giống nhau"); return; }
                        if (PasswordStore.setPassword(AdminActivity.this, a)) {
                            Toast.makeText(AdminActivity.this, "Đã đổi mật khẩu.", Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                        } else p1.setError("Không thể lưu mật khẩu");
                    }
                });
            }
        });
        dialog.show();
    }

    private EditText passwordInput(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private List<ResolveInfo> queryLauncherApps() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = new ArrayList<ResolveInfo>(getPackageManager().queryIntentActivities(i, PackageManager.MATCH_ALL));
        Collections.sort(list, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                return String.valueOf(a.loadLabel(getPackageManager())).compareToIgnoreCase(String.valueOf(b.loadLabel(getPackageManager())));
            }
        });
        return list;
    }

    private boolean isDefaultHome() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo r = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
    }

    private boolean isGuardEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(this, GuardService.class);
        return enabled.toLowerCase().contains(me.flattenToString().toLowerCase());
    }

    private GradientDrawable cardBackground(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp((int) radiusDp));
        g.setStroke(dp(1), 0xFFE1E7ED);
        return g;
    }

    private boolean isCompactWidth() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int widthDp = Math.round(dm.widthPixels / dm.density);
        return widthDp < 390;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
