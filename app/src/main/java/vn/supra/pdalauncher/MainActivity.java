package vn.supra.pdalauncher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int GRID_COLUMNS = 3;

    private LinearLayout appArea;
    private TextView clock;
    private TextView battery;
    private LinearLayout timeWarningCard;
    private TextView timeWarningText;
    private LinearLayout deviceIdentityCard;
    private ImageView deviceBarcode;
    private TextView deviceIdText;
    private TextView deviceIdHint;
    private String lastDeviceBarcodeValue;
    private boolean systemTimeInvalid;
    private final Handler handler = new Handler();
    private boolean batteryReceiverRegistered;
    private boolean timeReceiverRegistered;
    private String lastAllowedKey;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            long delay = 60000L - (now % 60000L);
            handler.postDelayed(this, delay);
        }
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateClock();
            refreshTimeState();
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) return;
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int percent = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;
            updateBatteryUi(percent, isCharging(intent));
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        PasswordStore.ensureInitialized(this);
        buildUi();
        updateDeviceIdentityUi();
        maybeRequestPhoneStatePermission();
        applyImmersive();
    }

    @Override protected void onResume() {
        super.onResume();
        Prefs.clearTimeFixSession(this);
        loadAllowedApps();
        refreshTimeState();
        updateDeviceIdentityUi();
        applyImmersive();

        handler.removeCallbacks(clockTick);
        handler.post(clockTick);

        if (!timeReceiverRegistered) {
            IntentFilter timeFilter = new IntentFilter();
            timeFilter.addAction(Intent.ACTION_TIME_CHANGED);
            timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            timeFilter.addAction(Intent.ACTION_DATE_CHANGED);
            registerReceiver(timeReceiver, timeFilter);
            timeReceiverRegistered = true;
        }

        if (!batteryReceiverRegistered) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent sticky = registerReceiver(batteryReceiver, filter);
            batteryReceiverRegistered = true;
            if (sticky != null) {
                int level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int percent = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;
                updateBatteryUi(percent, isCharging(sticky));
            }
        }
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
        if (timeReceiverRegistered) {
            try { unregisterReceiver(timeReceiver); } catch (Exception ignored) { }
            timeReceiverRegistered = false;
        }
        if (batteryReceiverRegistered) {
            try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) { }
            batteryReceiverRegistered = false;
        }
    }

    @Override public void onBackPressed() { }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    private void buildUi() {
        final boolean compact = isCompactWidth();
        final int side = dp(compact ? 12 : 16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(side, dp(10), side, dp(8));
        root.setBackgroundColor(0xFFF5F7FA);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView brandIcon = new ImageView(this);
        brandIcon.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
        brandIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int brandSize = dp(compact ? 40 : 44);
        LinearLayout.LayoutParams brandIconLp = new LinearLayout.LayoutParams(brandSize, brandSize);
        brandIconLp.setMargins(0, 0, dp(9), 0);
        topRow.addView(brandIcon, brandIconLp);

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("Launcher PDA");
        title.setTextSize(compact ? 19f : 21f);
        title.setTextColor(0xFF152238);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleBlock.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView version = new TextView(this);
        version.setText("Phiên bản " + BuildConfig.VERSION_NAME);
        version.setTextSize(compact ? 10.5f : 11f);
        version.setTextColor(0xFF64748B);
        version.setSingleLine(true);
        titleBlock.addView(version, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        topRow.addView(titleBlock, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageView settings = new ImageView(this);
        settings.setImageResource(android.R.drawable.ic_menu_preferences);
        int settingsSize = dp(compact ? 42 : 44);
        settings.setPadding(dp(10), dp(10), dp(10), dp(10));
        settings.setBackground(roundRect(0xFFFFFFFF, 12f, true));
        settings.setContentDescription("Cài đặt quản trị");
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAdminLogin(); }
        });
        topRow.addView(settings, new LinearLayout.LayoutParams(settingsSize, settingsSize));
        root.addView(topRow);

        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        metaRow.setPadding(brandSize + dp(9), dp(4), 0, dp(9));

        clock = new TextView(this);
        clock.setTextSize(compact ? 13f : 14f);
        clock.setTextColor(0xFF475569);
        clock.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        clock.setSingleLine(true);
        clock.setEllipsize(TextUtils.TruncateAt.END);
        metaRow.addView(clock, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        battery = new TextView(this);
        battery.setGravity(Gravity.CENTER);
        battery.setTextSize(compact ? 11.5f : 12f);
        battery.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        battery.setPadding(dp(9), dp(5), dp(9), dp(5));
        battery.setBackground(roundRect(0xFFFFFFFF, 12f, true));
        metaRow.addView(battery, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(metaRow);

        timeWarningCard = new LinearLayout(this);
        timeWarningCard.setOrientation(LinearLayout.VERTICAL);
        timeWarningCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        timeWarningCard.setBackground(roundRect(0xFFFFF7E6, 13f, true));
        timeWarningCard.setVisibility(View.GONE);

        timeWarningText = new TextView(this);
        timeWarningText.setTextSize(compact ? 11.5f : 12.5f);
        timeWarningText.setTextColor(0xFF8A4B08);
        timeWarningText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        timeWarningText.setMaxLines(3);
        timeWarningText.setEllipsize(TextUtils.TruncateAt.END);
        timeWarningCard.addView(timeWarningText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout timeActions = new LinearLayout(this);
        timeActions.setOrientation(LinearLayout.HORIZONTAL);
        timeActions.setGravity(Gravity.CENTER_VERTICAL);

        Button wifiSettings = new Button(this);
        wifiSettings.setText("Wi-Fi");
        wifiSettings.setTextSize(compact ? 11f : 12f);
        wifiSettings.setAllCaps(false);
        wifiSettings.setSingleLine(true);
        wifiSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openWifiSettings(); }
        });
        LinearLayout.LayoutParams wifiButtonLp = new LinearLayout.LayoutParams(
                0, dp(compact ? 42 : 44), 1f);
        wifiButtonLp.setMargins(0, 0, dp(4), 0);
        timeActions.addView(wifiSettings, wifiButtonLp);

        Button timeSettings = new Button(this);
        timeSettings.setText("Ngày giờ");
        timeSettings.setTextSize(compact ? 11f : 12f);
        timeSettings.setAllCaps(false);
        timeSettings.setSingleLine(true);
        timeSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openDateTimeSettings(); }
        });
        LinearLayout.LayoutParams timeButtonLp = new LinearLayout.LayoutParams(
                0, dp(compact ? 42 : 44), 1f);
        timeButtonLp.setMargins(dp(4), 0, 0, 0);
        timeActions.addView(timeSettings, timeButtonLp);

        LinearLayout.LayoutParams timeActionsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        timeActionsLp.setMargins(0, dp(7), 0, 0);
        timeWarningCard.addView(timeActions, timeActionsLp);

        LinearLayout.LayoutParams warningLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        warningLp.setMargins(0, 0, 0, dp(8));
        root.addView(timeWarningCard, warningLp);

        View divider = new View(this);
        divider.setBackgroundColor(0xFFE2E7EC);
        root.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        appArea = new LinearLayout(this);
        appArea.setOrientation(LinearLayout.VERTICAL);
        appArea.setPadding(0, dp(10), 0, dp(4));
        scroll.addView(appArea);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        deviceIdentityCard = new LinearLayout(this);
        deviceIdentityCard.setOrientation(LinearLayout.VERTICAL);
        deviceIdentityCard.setGravity(Gravity.CENTER);
        deviceIdentityCard.setPadding(dp(10), dp(7), dp(10), dp(7));
        deviceIdentityCard.setBackground(roundRect(0xFFFFFFFF, 13f, true));
        deviceIdentityCard.setClickable(true);
        deviceIdentityCard.setFocusable(true);
        deviceIdentityCard.setContentDescription("Mã thiết bị PDA");
        deviceIdentityCard.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                requestPhoneStatePermission(true);
            }
        });

        deviceBarcode = new ImageView(this);
        deviceBarcode.setScaleType(ImageView.ScaleType.FIT_CENTER);
        deviceBarcode.setAdjustViewBounds(false);
        deviceIdentityCard.addView(deviceBarcode, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(compact ? 44 : 50)));

        deviceIdText = new TextView(this);
        deviceIdText.setGravity(Gravity.CENTER);
        deviceIdText.setTextSize(compact ? 11.5f : 12.5f);
        deviceIdText.setTextColor(0xFF152238);
        deviceIdText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        deviceIdText.setSingleLine(true);
        deviceIdText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        deviceIdText.setPadding(dp(2), dp(4), dp(2), 0);
        deviceIdentityCard.addView(deviceIdText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        deviceIdHint = new TextView(this);
        deviceIdHint.setGravity(Gravity.CENTER);
        deviceIdHint.setTextSize(compact ? 9f : 9.5f);
        deviceIdHint.setTextColor(0xFF64748B);
        deviceIdHint.setSingleLine(true);
        deviceIdHint.setEllipsize(TextUtils.TruncateAt.END);
        deviceIdentityCard.addView(deviceIdHint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams identityLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        identityLp.setMargins(0, dp(4), 0, dp(2));
        root.addView(deviceIdentityCard, identityLp);

        TextView footer = new TextView(this);
        footer.setText("Phát triển hệ thống - tamnv2 | Pick Pack 1291");
        footer.setTextSize(compact ? 9f : 9.5f);
        footer.setTextColor(0xFF94A3B8);
        footer.setGravity(Gravity.END);
        footer.setSingleLine(true);
        footer.setEllipsize(TextUtils.TruncateAt.END);
        footer.setPadding(0, dp(4), 0, 0);
        root.addView(footer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void loadAllowedApps() {
        if (appArea == null) return;

        Set<String> allowed = Prefs.getAllowed(this);
        List<String> allowedSorted = new ArrayList<String>(allowed);
        Collections.sort(allowedSorted);
        String allowedKey = TextUtils.join("|", allowedSorted);
        if (allowedKey.equals(lastAllowedKey) && appArea.getChildCount() > 0) return;
        appArea.removeAllViews();
        lastAllowedKey = allowedKey;

        List<ResolveInfo> apps = queryLauncherApps();
        List<ResolveInfo> visible = new ArrayList<ResolveInfo>();
        Set<String> seen = new HashSet<String>();

        for (ResolveInfo r : apps) {
            if (r.activityInfo == null) continue;
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            if (allowed.contains(pkg) && seen.add(pkg)) visible.add(r);
        }

        if (visible.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Chưa có ứng dụng nào được hiển thị");
            empty.setGravity(Gravity.CENTER);
            empty.setTextSize(isCompactWidth() ? 14f : 15f);
            empty.setTextColor(0xFF64748B);
            empty.setPadding(dp(16), dp(42), dp(16), dp(42));
            empty.setBackground(roundRect(0xFFFFFFFF, 16f, true));
            appArea.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            return;
        }

        final int tileHeight = calculateTileHeightDp();
        LinearLayout row = null;

        for (int index = 0; index < visible.size(); index++) {
            if (index % GRID_COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setBaselineAligned(false);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.setMargins(0, 0, 0, dp(8));
                appArea.addView(row, rowLp);
            }

            LinearLayout.LayoutParams tileLp =
                    new LinearLayout.LayoutParams(0, dp(tileHeight), 1f);
            tileLp.setMargins(dp(3), 0, dp(3), 0);
            row.addView(createTile(visible.get(index)), tileLp);
        }

        int remainder = visible.size() % GRID_COLUMNS;
        if (row != null && remainder != 0) {
            for (int i = remainder; i < GRID_COLUMNS; i++) {
                LinearLayout.LayoutParams filler = new LinearLayout.LayoutParams(0, dp(1), 1f);
                filler.setMargins(dp(3), 0, dp(3), 0);
                row.addView(new View(this), filler);
            }
        }
    }

    private View createTile(final ResolveInfo info) {
        final boolean compact = isCompactWidth();
        final int iconDp = calculateIconSizeDp();

        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(5), dp(8), dp(5), dp(7));
        tile.setBackground(roundRect(0xFFFFFFFF, 15f, true));
        tile.setClickable(true);
        tile.setFocusable(true);

        ImageView icon = new ImageView(this);
        Drawable drawable = info.loadIcon(getPackageManager());
        icon.setImageDrawable(drawable);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));

        TextView label = new TextView(this);
        label.setText(info.loadLabel(getPackageManager()));
        label.setTextSize(compact ? 11.2f : 12f);
        label.setTextColor(0xFF263445);
        label.setGravity(Gravity.CENTER);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setIncludeFontPadding(false);
        label.setLineSpacing(0f, 0.94f);
        label.setPadding(dp(2), dp(7), dp(2), 0);
        tile.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final String pkg = info.activityInfo.packageName;
        tile.setContentDescription(String.valueOf(info.loadLabel(getPackageManager())));
        tile.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (isSystemTimeInvalid()) {
                    refreshTimeState();
                    Toast.makeText(MainActivity.this,
                            "Ngày giờ PDA chưa đúng. Hãy đồng bộ ngày giờ trước.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (launch == null) throw new IllegalStateException("No launch intent");
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "Không thể mở ứng dụng.", Toast.LENGTH_SHORT).show();
                }
            }
        });
        return tile;
    }

    private List<ResolveInfo> queryLauncherApps() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> result = new ArrayList<ResolveInfo>(
                getPackageManager().queryIntentActivities(i, PackageManager.MATCH_ALL));
        Collections.sort(result, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                return String.valueOf(a.loadLabel(getPackageManager()))
                        .compareToIgnoreCase(String.valueOf(b.loadLabel(getPackageManager())));
            }
        });
        return result;
    }

    private void showAdminLogin() {
        final EditText input = new EditText(this);
        input.setHint("Nhập mật khẩu quản trị");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setPadding(dp(16), dp(6), dp(16), dp(6));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Cài đặt quản trị")
                .setView(input)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Mở", null)
                .create();

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String value = input.getText().toString();
                        if (PasswordStore.verify(MainActivity.this, value)
                                || PasswordStore.verifyRecovery(value)) {
                            Prefs.beginAdminSession(MainActivity.this);
                            dialog.dismiss();
                            startActivity(new Intent(MainActivity.this, AdminActivity.class));
                        } else {
                            input.setError("Mật khẩu không đúng");
                        }
                    }
                });
            }
        });
        dialog.show();
    }

    private void refreshTimeState() {
        boolean autoTime = readGlobalFlag(Settings.Global.AUTO_TIME);
        boolean autoTimeZone = readGlobalFlag(Settings.Global.AUTO_TIME_ZONE);
        systemTimeInvalid = isSystemTimeInvalid();

        boolean showWarning = !autoTime || !autoTimeZone || systemTimeInvalid;
        if (timeWarningCard != null) {
            timeWarningCard.setVisibility(showWarning ? View.VISIBLE : View.GONE);
        }
        if (appArea != null) {
            appArea.setAlpha(systemTimeInvalid ? 0.55f : 1f);
        }
        if (!showWarning || timeWarningText == null) return;

        if (systemTimeInvalid && autoTime && autoTimeZone) {
            timeWarningText.setText(
                    "Ngày giờ PDA chưa đồng bộ. Kiểm tra Wi-Fi/mạng và chờ Android cập nhật giờ tự động.");
        } else if (!autoTime && !autoTimeZone) {
            timeWarningText.setText(
                    "Hãy bật Ngày giờ tự động và Múi giờ tự động.");
        } else if (!autoTime) {
            timeWarningText.setText("Hãy bật Ngày giờ tự động.");
        } else if (!autoTimeZone) {
            timeWarningText.setText("Hãy bật Múi giờ tự động.");
        } else {
            timeWarningText.setText("Ngày giờ PDA chưa đúng. Hãy đồng bộ lại trước khi làm việc.");
        }
    }

    private boolean readGlobalFlag(String key) {
        try {
            return Settings.Global.getInt(getContentResolver(), key, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isSystemTimeInvalid() {
        try {
            int year = Calendar.getInstance().get(Calendar.YEAR);
            return year < 2025 || year > 2100;
        } catch (Exception e) {
            return true;
        }
    }

    private void openWifiSettings() {
        Prefs.beginTimeFixSession(this);
        try {
            Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            } else {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        } catch (Exception e) {
            Prefs.clearTimeFixSession(this);
            Toast.makeText(this, "Không mở được cài đặt Wi-Fi.", Toast.LENGTH_SHORT).show();
        }
    }

    private void openDateTimeSettings() {
        Prefs.beginTimeFixSession(this);
        try {
            Intent intent = new Intent(Settings.ACTION_DATE_SETTINGS);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            } else {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        } catch (Exception e) {
            Prefs.clearTimeFixSession(this);
            Toast.makeText(this, "Không mở được cài đặt ngày giờ.", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateClock() {
        if (clock != null) {
            clock.setText(new SimpleDateFormat(
                    "HH:mm  •  dd/MM/yyyy", Locale.getDefault()).format(new Date()));
        }
    }

    private boolean isCharging(Intent batteryIntent) {
        if (batteryIntent == null) return false;
        int status = batteryIntent.getIntExtra(
                BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        int plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        return plugged > 0
                || status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    private void updateBatteryUi(int level, boolean charging) {
        if (battery == null) return;
        if (!Prefs.isShowBattery(this)) {
            battery.setVisibility(View.GONE);
            return;
        }

        battery.setVisibility(View.VISIBLE);
        if (charging) {
            battery.setText(level >= 0 ? ("Đang sạc " + level + "%") : "Đang sạc --%");
        } else {
            battery.setText(level >= 0 ? ("Pin " + level + "%") : "Pin --%");
        }

        if (level >= 0 && level <= 15) {
            battery.setTextColor(0xFFC62828);
        } else if (level >= 0 && level <= 30) {
            battery.setTextColor(0xFFC46A1A);
        } else {
            battery.setTextColor(0xFF16784A);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PHONE_STATE) {
            updateDeviceIdentityUi();
        }
    }

    private void maybeRequestPhoneStatePermission() {
        if (!DeviceIdentifier.isTargetPda()) return;
        DeviceIdentifier.Result result = DeviceIdentifier.resolve(this);
        if (result.isAvailable()) return;
        requestPhoneStatePermission(false);
    }

    private void requestPhoneStatePermission(boolean force) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            updateDeviceIdentityUi();
            return;
        }

        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED) {
            updateDeviceIdentityUi();
            return;
        }

        android.content.SharedPreferences prefs =
                getSharedPreferences("device_identifier_probe", MODE_PRIVATE);
        boolean alreadyRequested = prefs.getBoolean("phone_state_requested", false);
        if (!force && alreadyRequested) {
            updateDeviceIdentityUi();
            return;
        }

        prefs.edit().putBoolean("phone_state_requested", true).apply();
        requestPermissions(
                new String[] { Manifest.permission.READ_PHONE_STATE },
                REQUEST_PHONE_STATE);
    }

    private void updateDeviceIdentityUi() {
        if (deviceIdentityCard == null || deviceIdText == null
                || deviceIdHint == null || deviceBarcode == null) return;

        DeviceIdentifier.Result result = DeviceIdentifier.resolve(this);

        if (result.isAvailable()) {
            deviceIdText.setText(result.label + ": " + result.value);
            deviceIdHint.setText("Quét mã vạch hoặc đọc trực tiếp số thiết bị");
            deviceBarcode.setVisibility(View.VISIBLE);

            if (!result.value.equals(lastDeviceBarcodeValue)) {
                try {
                    int barcodeWidth = dp(Math.max(240, screenWidthDp() - 48));
                    int barcodeHeight = dp(isCompactWidth() ? 44 : 50);
                    deviceBarcode.setImageBitmap(
                            BarcodeUtils.code128(result.value, barcodeWidth, barcodeHeight));
                    lastDeviceBarcodeValue = result.value;
                } catch (Exception e) {
                    deviceBarcode.setImageDrawable(null);
                    deviceBarcode.setVisibility(View.GONE);
                    deviceIdHint.setText("Đã đọc được số thiết bị nhưng chưa tạo được mã vạch");
                }
            }
            return;
        }

        lastDeviceBarcodeValue = null;
        deviceBarcode.setImageDrawable(null);
        deviceBarcode.setVisibility(View.GONE);
        deviceIdText.setText(result.label + ": Không đọc được");

        boolean missingPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED;
        if (missingPermission) {
            deviceIdHint.setText("Chạm vào đây để cấp quyền Điện thoại và thử lại");
        } else {
            deviceIdHint.setText(result.detail + " • firmware có thể đang hạn chế quyền");
        }
    }

    private void applyImmersive() {
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private boolean isCompactWidth() {
        return screenWidthDp() < 390;
    }

    private int calculateTileHeightDp() {
        int width = screenWidthDp();
        int side = isCompactWidth() ? 12 : 16;
        int available = width - (side * 2) - 18;
        int cell = Math.max(88, available / GRID_COLUMNS);
        return clamp(cell + 12, 108, 124);
    }

    private int calculateIconSizeDp() {
        int width = screenWidthDp();
        int side = isCompactWidth() ? 12 : 16;
        int available = width - (side * 2) - 18;
        int cell = Math.max(88, available / GRID_COLUMNS);
        return clamp(Math.round(cell * 0.42f), 40, 50);
    }

    private int screenWidthDp() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return Math.round(dm.widthPixels / dm.density);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private GradientDrawable roundRect(int color, float radiusDp, boolean border) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(Math.round(radiusDp)));
        if (border) g.setStroke(dp(1), 0xFFE2E7EC);
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
