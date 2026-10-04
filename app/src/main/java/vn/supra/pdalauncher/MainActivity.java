package vn.supra.pdalauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private LinearLayout appArea;
    private TextView clock;
    private final Handler handler = new Handler();
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (clock != null) {
                clock.setText(new SimpleDateFormat("HH:mm\ndd/MM/yyyy", Locale.getDefault()).format(new Date()));
            }
            handler.postDelayed(this, 10000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        PasswordStore.ensureInitialized(this);
        buildUi();
        applyImmersive();
    }

    @Override protected void onResume() {
        super.onResume();
        loadAllowedApps();
        applyImmersive();
        handler.removeCallbacks(clockTick);
        handler.post(clockTick);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
    }

    @Override public void onBackPressed() { }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(14));
        root.setBackgroundColor(0xFFF5F7FA);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(12));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);

        ImageView brandIcon = new ImageView(this);
        brandIcon.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
        brandIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams brandIconLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        brandIconLp.setMargins(0, 0, dp(12), 0);
        brand.addView(brandIcon, brandIconLp);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("SUPRA PDA");
        title.setTextSize(22f);
        title.setTextColor(0xFF152238);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        TextView sub = new TextView(this);
        sub.setText("Màn hình làm việc");
        sub.setTextSize(12.5f);
        sub.setTextColor(0xFF6B7785);
        titleBox.addView(title);
        titleBox.addView(sub);
        brand.addView(titleBox);
        header.addView(brand, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        clock = new TextView(this);
        clock.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        clock.setTextSize(12.5f);
        clock.setTextColor(0xFF51606F);
        LinearLayout.LayoutParams clockLp = new LinearLayout.LayoutParams(dp(104), LinearLayout.LayoutParams.WRAP_CONTENT);
        clockLp.setMargins(dp(8), 0, dp(8), 0);
        header.addView(clock, clockLp);

        ImageView settings = new ImageView(this);
        settings.setImageResource(android.R.drawable.ic_menu_preferences);
        settings.setPadding(dp(12), dp(12), dp(12), dp(12));
        settings.setBackground(roundRect(0xFFFFFFFF, 14f, true));
        settings.setContentDescription("Cài đặt quản trị");
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAdminLogin(); }
        });
        header.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header);

        View divider = new View(this);
        divider.setBackgroundColor(0xFFE2E7EC);
        root.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        appArea = new LinearLayout(this);
        appArea.setOrientation(LinearLayout.VERTICAL);
        appArea.setPadding(0, dp(16), 0, dp(8));
        scroll.addView(appArea);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void loadAllowedApps() {
        appArea.removeAllViews();
        Set<String> allowed = Prefs.getAllowed(this);
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
            LinearLayout emptyCard = new LinearLayout(this);
            emptyCard.setOrientation(LinearLayout.VERTICAL);
            emptyCard.setGravity(Gravity.CENTER);
            emptyCard.setPadding(dp(22), dp(56), dp(22), dp(56));
            emptyCard.setBackground(roundRect(0xFFFFFFFF, 18f, true));

            TextView icon = new TextView(this);
            icon.setText("＋");
            icon.setTextSize(36f);
            icon.setTextColor(0xFF1E5CC8);
            icon.setGravity(Gravity.CENTER);
            emptyCard.addView(icon);

            TextView empty = new TextView(this);
            empty.setText("Chưa có ứng dụng nào được hiển thị");
            empty.setGravity(Gravity.CENTER);
            empty.setTextSize(16f);
            empty.setTextColor(0xFF475569);
            empty.setPadding(0, dp(10), 0, 0);
            emptyCard.addView(empty);

            TextView hint = new TextView(this);
            hint.setText("Mở biểu tượng cài đặt ở góc phải để chọn ứng dụng.");
            hint.setGravity(Gravity.CENTER);
            hint.setTextSize(12.5f);
            hint.setTextColor(0xFF8793A0);
            hint.setPadding(0, dp(5), 0, 0);
            emptyCard.addView(hint);

            appArea.addView(emptyCard, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            return;
        }

        LinearLayout row = null;
        int index = 0;
        for (ResolveInfo r : visible) {
            if (index % 4 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.setMargins(0, 0, 0, dp(10));
                appArea.addView(row, rowLp);
            }
            LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(0, dp(132), 1f);
            tileLp.setMargins(dp(5), 0, dp(5), 0);
            row.addView(createTile(r), tileLp);
            index++;
        }
        if (row != null && index % 4 != 0) {
            int missing = 4 - (index % 4);
            for (int i = 0; i < missing; i++) {
                LinearLayout.LayoutParams filler = new LinearLayout.LayoutParams(0, dp(1), 1f);
                filler.setMargins(dp(5), 0, dp(5), 0);
                row.addView(new View(this), filler);
            }
        }
    }

    private View createTile(final ResolveInfo info) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(8), dp(12), dp(8), dp(10));
        tile.setBackground(roundRect(0xFFFFFFFF, 18f, true));

        ImageView icon = new ImageView(this);
        Drawable d = info.loadIcon(getPackageManager());
        icon.setImageDrawable(d);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(58), dp(58)));

        TextView label = new TextView(this);
        label.setText(info.loadLabel(getPackageManager()));
        label.setTextSize(12.5f);
        label.setTextColor(0xFF263445);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setPadding(0, dp(8), 0, 0);
        tile.addView(label, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final String pkg = info.activityInfo.packageName;
        tile.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (launch == null) throw new IllegalStateException("No launch intent");
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Không thể mở ứng dụng.", Toast.LENGTH_SHORT).show();
                }
            }
        });
        return tile;
    }

    private List<ResolveInfo> queryLauncherApps() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> result = new ArrayList<ResolveInfo>(getPackageManager().queryIntentActivities(i, PackageManager.MATCH_ALL));
        Collections.sort(result, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                return String.valueOf(a.loadLabel(getPackageManager())).compareToIgnoreCase(String.valueOf(b.loadLabel(getPackageManager())));
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
                        if (PasswordStore.verify(MainActivity.this, value) || PasswordStore.verifyRecovery(value)) {
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

    private GradientDrawable roundRect(int color, float radiusDp, boolean border) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp((int) radiusDp));
        if (border) g.setStroke(dp(1), 0xFFE2E7EC);
        return g;
    }

    private void applyImmersive() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
