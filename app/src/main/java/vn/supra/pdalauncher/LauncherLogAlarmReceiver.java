package vn.supra.pdalauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// A single OS alarm wakes this receiver for each staggered upload window.
// It never starts a minute-by-minute timer or performs networking on main.
public final class LauncherLogAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        LauncherDiagnostics.onScheduledLogAlarm(context.getApplicationContext());
    }
}
