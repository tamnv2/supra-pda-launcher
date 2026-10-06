package vn.supra.pdalauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;

public class BatteryAlertReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "launcher_pda_battery";
    private static final int NOTIFICATION_ID = 1291;

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        if (Intent.ACTION_BATTERY_OKAY.equals(intent.getAction())) {
            LauncherDiagnostics.recordOperationalEvent(
                    context, "battery_okay_signal", "");
            cancel(context);
            return;
        }

        if (!Intent.ACTION_BATTERY_LOW.equals(intent.getAction())) return;
        LauncherDiagnostics.recordOperationalEvent(
                context, "battery_low_signal", "");
        if (!Prefs.isLowBatteryAlertEnabled(context)) return;

        showLowBattery(context);
    }

    static void cancel(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    private void showLowBattery(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Cảnh báo pin",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Cảnh báo khi pin PDA yếu");
            manager.createNotificationChannel(channel);
        }

        int percent = getBatteryPercent(context);
        String message = percent >= 0
                ? "Pin còn " + percent + "%. Hãy cắm sạc PDA."
                : "Pin PDA đang yếu. Hãy cắm sạc.";

        Intent open = new Intent(context, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getActivity(context, 0, open, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);

        builder.setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Pin yếu")
                .setContentText(message)
                .setContentIntent(pending)
                .setAutoCancel(false)
                .setOngoing(false)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL);

        manager.notify(NOTIFICATION_ID, builder.build());
    }

    private int getBatteryPercent(Context context) {
        try {
            Intent battery = context.registerReceiver(
                    null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return -1;
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) return -1;
            return Math.round(level * 100f / scale);
        } catch (Exception e) {
            return -1;
        }
    }
}
