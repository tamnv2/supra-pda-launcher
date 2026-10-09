package vn.supra.pdalauncher;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class LauncherDiagnostics {
    private static final String API_URL =
            "https://inventory-beta.supra.cc.cd/api/pda/launcher/logs";
    private static final String PREFS = "launcher_diagnostics";
    private static final String LOG_DIR = "launcher_diagnostics";
    private static final String FILE_PREFIX = "launcher-log-";
    private static final String FILE_SUFFIX = ".jsonl";
    private static final String LATE_SUFFIX = "-late.jsonl";
    private static final long SAMPLE_INTERVAL_MS = 15L * 60L * 1000L;
    private static final long DT50_FULL_SAMPLE_INTERVAL_MS = 3L * 60L * 60L * 1000L;
    private static final long MAX_REGULAR_LOG_BYTES = 118_000L;
    private static final long MAX_CRITICAL_LOG_BYTES = 138_000L;
    private static final int MAX_UPLOAD_EVENTS = 180;
    // Bound each archived segment under the server's 180-event limit.
    private static final int MAX_SEGMENT_EVENTS = 140;
    private static final long MAX_SEGMENT_BYTES = 70_000L;
    private static final Map<String, Integer> SEGMENT_COUNTS = new HashMap<>();
    private static final int JOB_ID = 12910323;
    private static final int UPLOAD_JOB_ID = 12910330;
    private static final int DRAIN_JOB_ID = 12910331;
    private static final int UPLOAD_FAILED = 0;
    private static final int UPLOAD_BUFFERED = 1;
    private static final int UPLOAD_DRIVE_SYNCED = 2;
    private static final int RECEIPT_MISSING = 3;
    private static final long JOB_INTERVAL_MS = 3L * 60L * 60L * 1000L;
    private static final TimeZone VN_TZ = TimeZone.getTimeZone("Asia/Ho_Chi_Minh");
    private static final long[] RETRY_DELAYS_MS = new long[] {
            15L * 60L * 1000L,
            30L * 60L * 1000L,
            60L * 60L * 1000L,
            2L * 60L * 60L * 1000L,
            6L * 60L * 60L * 1000L
    };

    private static final Object FILE_LOCK = new Object();
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final AtomicBoolean UPLOAD_IN_FLIGHT = new AtomicBoolean(false);
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "supra-launcher-diagnostics");
        t.setDaemon(true);
        return t;
    });

    private static volatile long lastBatterySignalAt;
    private static volatile int lastBatteryLevel = Integer.MIN_VALUE;
    private static volatile int lastBatteryStatus = Integer.MIN_VALUE;
    private static volatile int lastBatteryPlugged = Integer.MIN_VALUE;

    private LauncherDiagnostics() {}

    static void initialize(Context context) {
        final Context app = context.getApplicationContext();
        scheduleJob(app);
        scheduleLogAlarms(app);
        installCrashHandler(app);

        if (INITIALIZED.compareAndSet(false, true)) {
            IO.execute(() -> {
                try {
                    JSONObject data = captureSystemSnapshot(app, isDt50());
                    appendEventSync(app, "process_start", data, true);
                    cleanupOldFiles(app);
                    maintenanceSync(app, false, false);
                } catch (Throwable ignored) { }
            });
        } else {
            maybeUploadPending(app);
        }
    }

    // The clock UI is already updated once per minute. No minute-based log polling.
    static void tick(Context context) { }

    static void onScheduledLogAlarm(Context context) {
        scheduleLogAlarms(context.getApplicationContext());
        scheduleUploadJob(context.getApplicationContext());
    }

    static void onSystemClockOrBoot(Context context) {
        scheduleLogAlarms(context.getApplicationContext());
    }

    private static int slotMinute(String deviceKey, String date, int baseMinutes) {
        // Deterministic pseudo-random +/- 15 minutes per device, day, and slot.
        // Each device remains stable after reboot, but fleet distribution varies by day.
        try {
            String digest = sha256Hex(deviceKey + "|" + date + "|" + baseMinutes);
            int value = (int) Long.parseLong(digest.substring(0, 7), 16);
            return baseMinutes + (value % 31) - 15;
        } catch (Throwable ignored) {
            return baseMinutes;
        }
    }

    private static void scheduleLogAlarms(Context context) {
        try {
            AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarms == null) return;
            String key = Prefs.getRegistryDeviceKey(context);
            if (key == null || !key.matches("[a-fA-F0-9]{64}")) {
                key = "unregistered-" + android.os.Build.MODEL;
            }
            for (int slot : new int[] { 13 * 60 + 30, 21 * 60 + 30 }) {
                Calendar when = Calendar.getInstance(VN_TZ);
                String date = dateKey(when.getTime());
                int offsetMinutes = slotMinute(key, date, slot);
                when.set(Calendar.HOUR_OF_DAY, offsetMinutes / 60);
                when.set(Calendar.MINUTE, offsetMinutes % 60);
                when.set(Calendar.SECOND, 0);
                when.set(Calendar.MILLISECOND, 0);
                if (when.getTimeInMillis() <= System.currentTimeMillis()) {
                    when.add(Calendar.DAY_OF_YEAR, 1);
                    date = dateKey(when.getTime());
                    offsetMinutes = slotMinute(key, date, slot);
                    when.set(Calendar.HOUR_OF_DAY, offsetMinutes / 60);
                    when.set(Calendar.MINUTE, offsetMinutes % 60);
                }
                Intent intent = new Intent(context, LauncherLogAlarmReceiver.class);
                intent.setAction("vn.supra.pdalauncher.LOG_UPLOAD_" + slot);
                PendingIntent pending = PendingIntent.getBroadcast(context, slot, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                if (android.os.Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when.getTimeInMillis(), pending);
                } else {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when.getTimeInMillis(), pending);
                }
            }
        } catch (Throwable error) {
            recordOperationalEvent(context, "log_alarm_schedule_error", error.getClass().getSimpleName());
        }
    }

    private static void scheduleUploadJob(Context context) {
        try {
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) return;
            JobInfo job = new JobInfo.Builder(UPLOAD_JOB_ID,
                    new ComponentName(context, LauncherDiagnosticJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setOverrideDeadline(15L * 60L * 1000L)
                    .build();
            scheduler.schedule(job);
        } catch (Throwable error) {
            recordOperationalEvent(context, "upload_job_schedule_error", error.getClass().getSimpleName());
        }
    }

    private static void scheduleDeferredDrain(Context context, long delayMs) {
        try {
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) return;
            JobInfo job = new JobInfo.Builder(DRAIN_JOB_ID,
                    new ComponentName(context, LauncherDiagnosticJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(Math.max(45000L, delayMs))
                    .setOverrideDeadline(Math.max(45000L, delayMs) + 15L * 60L * 1000L)
                    .build();
            scheduler.schedule(job);
        } catch (Throwable ignored) { }
    }

    static void runScheduledUpload(Context context) {
        final Context app = context.getApplicationContext();
        try {
            JSONObject event = new JSONObject();
            event.put("scheduled_window", "13:30/21:30 +/-15min Asia/Ho_Chi_Minh");
            event.put("activated_at", isoNow());
            appendEventSync(app, "scheduled_upload_wakeup", event, true);
        } catch (Throwable ignored) { }
        maintenanceSync(app, true, true);
    }

    private static String dateKey(Date date) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(VN_TZ);
        return f.format(date);
    }

    static void runPeriodicMaintenance(Context context) {
        maintenanceSync(context.getApplicationContext(), true, false);
    }

    static void onBatteryChanged(Context context, Intent batteryIntent) {
        if (batteryIntent == null || !isDt50()) return;
        int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int percent = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
        int status = batteryIntent.getIntExtra(
                BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        int plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);

        long now = System.currentTimeMillis();
        boolean chargingTransition =
                status != lastBatteryStatus || plugged != lastBatteryPlugged;
        boolean levelChanged = percent >= 0 && percent != lastBatteryLevel;
        boolean enoughTime = now - lastBatterySignalAt >= 5L * 60L * 1000L;

        lastBatteryLevel = percent;
        lastBatteryStatus = status;
        lastBatteryPlugged = plugged;

        if (!chargingTransition && !(levelChanged && enoughTime)) return;
        lastBatterySignalAt = now;

        final Context app = context.getApplicationContext();
        final Intent copy = new Intent(batteryIntent);
        IO.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("reason", chargingTransition
                        ? "charging_state_changed" : "reported_level_changed");
                data.put("battery", captureBattery(app, copy, false));
                appendEventSync(app, "battery_signal", data, false);
            } catch (Throwable ignored) { }
        });
    }

    static void recordAppLaunch(Context context, String packageName) {
        final Context app = context.getApplicationContext();
        final String safePackage = safeText(packageName, 120);
        IO.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("package", safePackage);
                appendEventSync(app, "app_launch", data, false);
            } catch (Throwable ignored) { }
        });
    }

    static void recordSecurityEvent(Context context, String event, boolean success) {
        final Context app = context.getApplicationContext();
        final String safeEvent = safeText(event, 80);
        IO.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("action", safeEvent);
                data.put("success", success);
                appendEventSync(app, "security_event", data, true);
            } catch (Throwable ignored) { }
        });
    }

    static void recordUpdateEvent(Context context, String event, String detail) {
        final Context app = context.getApplicationContext();
        final String safeEvent = safeText(event, 80);
        final String safeDetail = safeText(detail, 120);
        IO.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("action", safeEvent);
                if (!safeDetail.isEmpty()) data.put("detail", safeDetail);
                appendEventSync(app, "update_event", data, true);
            } catch (Throwable ignored) { }
        });
    }

    static void recordOperationalEvent(Context context, String event, String detail) {
        final Context app = context.getApplicationContext();
        final String safeEvent = safeText(event, 80);
        final String safeDetail = safeText(detail, 120);
        IO.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("action", safeEvent);
                if (!safeDetail.isEmpty()) data.put("detail", safeDetail);
                appendEventSync(app, "operational_event", data, true);
            } catch (Throwable ignored) { }
        });
    }

    private static void maintenanceSync(Context context, boolean allowSample, boolean allowUpload) {
        try {
            if (allowSample && isDt50() && isOperationalSamplingWindow()) {
                SharedPreferences prefs = diagPrefs(context);
                long now = System.currentTimeMillis();
                long lastSample = prefs.getLong("last_sample_at", 0L);
                if (lastSample <= 0L || now - lastSample >= SAMPLE_INTERVAL_MS) {
                    long lastFull = prefs.getLong("last_full_sample_at", 0L);
                    long fullInterval = DT50_FULL_SAMPLE_INTERVAL_MS;
                    boolean full = lastFull <= 0L || now - lastFull >= fullInterval;
                    JSONObject data = new JSONObject();
                    JSONObject batterySample = captureBattery(context, null, full);
                    data.put("battery", batterySample);
                    if (isDt50()) {
                        data.put("battery_trend", trackDt50BatteryTrend(context, batterySample, now));
                    }
                    if (full) {
                        data.put("system", captureSystemSnapshot(context, false));
                    }
                    appendEventSync(context, full ? "battery_sample_full" : "battery_sample", data, true);
                    SharedPreferences.Editor editor = prefs.edit()
                            .putLong("last_sample_at", now);
                    if (full) editor.putLong("last_full_sample_at", now);
                    editor.apply();
                }
            }
            cleanupOldFiles(context);
            if (allowUpload) uploadPendingSync(context);
        } catch (Throwable ignored) { }
    }

    private static boolean isOperationalSamplingWindow() {
        Calendar calendar = Calendar.getInstance(VN_TZ);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int minute = calendar.get(Calendar.MINUTE);
        int minutes = hour * 60 + minute;
        return minutes >= 5 * 60 + 30 && minutes <= 22 * 60 + 5;
    }

    private static JSONObject captureSystemSnapshot(Context context, boolean includeBattery)
            throws Exception {
        JSONObject result = new JSONObject();
        JSONObject build = new JSONObject();
        build.put("manufacturer", safeText(Build.MANUFACTURER, 80));
        build.put("brand", safeText(Build.BRAND, 80));
        build.put("model", safeText(Build.MODEL, 100));
        build.put("device", safeText(Build.DEVICE, 100));
        build.put("product", safeText(Build.PRODUCT, 100));
        build.put("hardware", safeText(Build.HARDWARE, 100));
        build.put("sdk_int", Build.VERSION.SDK_INT);
        build.put("android_version", safeText(Build.VERSION.RELEASE, 40));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            build.put("security_patch", safeText(Build.VERSION.SECURITY_PATCH, 40));
        }
        if (isDt50()) {
            // Identify firmware variants without logging IMEI, serial or MEID.
            build.put("build_id", safeText(Build.ID, 80));
            build.put("firmware_display", safeText(Build.DISPLAY, 120));
            build.put("firmware_incremental", safeText(Build.VERSION.INCREMENTAL, 120));
            build.put("board", safeText(Build.BOARD, 80));
        }
        result.put("build", build);

        JSONObject launcher = new JSONObject();
        launcher.put("version_name", BuildConfig.VERSION_NAME);
        launcher.put("version_code", BuildConfig.VERSION_CODE);
        launcher.put("uptime_ms", SystemClock.elapsedRealtime());
        launcher.put("required_update_code",
                Prefs.getRequiredUpdateCode(context));
        launcher.put("guard_paused", Prefs.isGuardPaused(context));
        result.put("launcher", launcher);

        ActivityManager am =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            JSONObject memory = new JSONObject();
            memory.put("avail_mb", info.availMem / (1024L * 1024L));
            memory.put("total_mb", info.totalMem / (1024L * 1024L));
            memory.put("low_memory", info.lowMemory);
            memory.put("threshold_mb", info.threshold / (1024L * 1024L));
            result.put("memory", memory);
        }

        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            JSONObject storage = new JSONObject();
            storage.put("available_mb", stat.getAvailableBytes() / (1024L * 1024L));
            storage.put("total_mb", stat.getTotalBytes() / (1024L * 1024L));
            result.put("storage", storage);
        } catch (Throwable ignored) { }

        JSONObject clock = new JSONObject();
        clock.put("auto_time", readGlobalFlag(context, Settings.Global.AUTO_TIME));
        clock.put("auto_time_zone", readGlobalFlag(context, Settings.Global.AUTO_TIME_ZONE));
        clock.put("timezone", TimeZone.getDefault().getID());
        result.put("clock", clock);

        result.put("network", captureNetwork(context));
        if (includeBattery && isDt50()) result.put("battery", captureBattery(context, null, true));
        return result;
    }

    private static JSONObject captureNetwork(Context context) throws Exception {
        JSONObject out = new JSONObject();
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            out.put("connected", false);
            return out;
        }

        Network network = cm.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
        if (caps == null) {
            out.put("connected", false);
            return out;
        }
        out.put("connected", true);
        out.put("wifi", caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
        out.put("cellular", caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
        out.put("ethernet", caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        out.put("vpn", caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
        out.put("validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        out.put("metered", cm.isActiveNetworkMetered());
        return out;
    }

    private static JSONObject captureBattery(Context context, Intent supplied, boolean full)
            throws Exception {
        Intent battery = supplied;
        if (battery == null) {
            try {
                battery = context.registerReceiver(
                        null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            } catch (Throwable ignored) { }
        }

        JSONObject out = new JSONObject();
        out.put("capture_uptime_ms", SystemClock.elapsedRealtime());
        if (isDt50()) out.put("diagnostic_schema", "dt50-battery-v2");
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int percent = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;

            JSONObject broadcast = new JSONObject();
            broadcast.put("level", level);
            broadcast.put("scale", scale);
            broadcast.put("percent", percent);
            broadcast.put("status", battery.getIntExtra(
                    BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN));
            broadcast.put("health", battery.getIntExtra(
                    BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN));
            broadcast.put("plugged", battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                broadcast.put("battery_low_flag",
                        battery.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false));
            }
            broadcast.put("present",
                    battery.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true));
            broadcast.put("temperature_tenths_c",
                    battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE));
            broadcast.put("voltage_mv",
                    battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Integer.MIN_VALUE));
            broadcast.put("technology",
                    safeText(battery.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY), 40));
            out.put("broadcast", broadcast);
        }

        BatteryManager manager =
                (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (manager != null) {
            JSONObject properties = new JSONObject();
            putIntProperty(properties, "capacity_percent", manager,
                    BatteryManager.BATTERY_PROPERTY_CAPACITY);
            putIntProperty(properties, "charge_counter_uah", manager,
                    BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            putIntProperty(properties, "current_now_ua", manager,
                    BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            putIntProperty(properties, "current_average_ua", manager,
                    BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putIntProperty(properties, "status",
                        manager, BatteryManager.BATTERY_PROPERTY_STATUS);
            }
            try {
                properties.put("is_charging", manager.isCharging());
            } catch (Throwable ignored) { }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    long remainingMs = manager.computeChargeTimeRemaining();
                    if (remainingMs >= 0L) {
                        properties.put("charge_time_remaining_ms", remainingMs);
                    }
                } catch (Throwable ignored) { }
            }
            try {
                long energy = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);
                if (energy != Long.MIN_VALUE) properties.put("energy_counter_nwh", energy);
            } catch (Throwable ignored) { }
            out.put("properties", properties);
        }

        if (full && isDt50()) {
            out.put("power_supply", readPowerSupplySysfs());
            String dumpsys = readCommand(
                    new String[] { "/system/bin/dumpsys", "battery" }, 5000);
            if (!dumpsys.isEmpty()) out.put("dumpsys_battery", dumpsys);
            out.put("dumpsys_battery_available", !dumpsys.isEmpty());
        }
        // Lightweight local-only probe on DT50. No new HTTP requests or polling jobs.
        // Sysfs opens are costly on vendor firmware. Probe only in the
        // 3-hour full DT50 sample; keep charging events broadcast-only.
        if (full && isDt50()) out.put("dt50_sysfs_probe", readDt50BatteryProbe());
        out.put("source_comparison", compareBatterySources(out));
        return out;
    }

    private static void putIntProperty(
            JSONObject target, String key, BatteryManager manager, int propertyId) {
        try {
            int value = manager.getIntProperty(propertyId);
            if (value != Integer.MIN_VALUE) target.put(key, value);
        } catch (Throwable ignored) { }
    }

    private static JSONObject readPowerSupplySysfs() throws Exception {
        JSONObject result = new JSONObject();
        File root = new File("/sys/class/power_supply");
        File[] supplies = root.listFiles();
        if (supplies == null) return result;

        Arrays.sort(supplies, Comparator.comparing(File::getName));
        String[] fields = new String[] {
                "type", "status", "present", "online", "health", "technology",
                "capacity", "voltage_now", "current_now", "current_avg",
                "charge_counter", "charge_now", "charge_full", "charge_full_design",
                "energy_now", "energy_full", "energy_full_design",
                "voltage_avg", "voltage_ocv", "voltage_min_design",
                "voltage_max_design", "capacity_level", "charge_type",
                "time_to_empty_now", "time_to_full_now",
                "temp", "cycle_count"
        };

        int count = 0;
        for (File supply : supplies) {
            if (!supply.isDirectory() || count >= 12) continue;
            JSONObject values = new JSONObject();
            for (String field : fields) {
                String value = readSmallFile(new File(supply, field), 160);
                if (!value.isEmpty()) values.put(field, value);
            }
            if (values.length() > 0) {
                result.put(safeText(supply.getName(), 48), values);
                count++;
            }
        }
        return result;
    }


    static boolean isDt50Device() { return isDt50(); }

    private static boolean isDt50() {
        String identity = (Build.MANUFACTURER + " " + Build.MODEL + " "
                + Build.DEVICE + " " + Build.PRODUCT).toUpperCase(Locale.US);
        return identity.contains("DT50");
    }

    // Battery-only sysfs probe is DT50-specific. Scheduled samples are
    // at most every three hours; power-state transitions may add event samples.
    private static JSONObject readDt50BatteryProbe() throws Exception {
        JSONObject result = new JSONObject();
        File[] supplies = new File("/sys/class/power_supply").listFiles();
        if (supplies == null) {
            result.put("_state", "unavailable_or_denied");
            return result;
        }
        Arrays.sort(supplies, Comparator.comparing(File::getName));
        int found = 0;
        int readable = 0;
        String[] fields = new String[] {
                "type", "capacity", "capacity_level", "status", "health",
                "present", "online", "voltage_now", "voltage_avg",
                "voltage_ocv", "voltage_min_design", "voltage_max_design",
                "current_now", "current_avg", "charge_now", "charge_full",
                "charge_full_design", "charge_counter", "energy_now",
                "energy_full", "energy_full_design", "cycle_count",
                "charge_type", "temp", "time_to_empty_now", "time_to_full_now"
        };
        for (File supply : supplies) {
            if (!supply.isDirectory()) continue;
            String name = supply.getName();
            String lower = name.toLowerCase(Locale.US);
            String type = readSmallFile(new File(supply, "type"), 32);
            if (!"battery".equalsIgnoreCase(type)
                    && !lower.contains("battery") && !lower.contains("bms")) {
                continue;
            }
            found++;
            if (found > 3) break;
            JSONObject values = new JSONObject();
            for (String field : fields) {
                String value = readSmallFile(new File(supply, field), 48);
                if (!value.isEmpty()) values.put(field, value);
            }
            if (values.length() > 0) {
                result.put(safeText(name, 48), values);
                readable++;
            }
        }
        result.put("_battery_supplies_found", found);
        result.put("_readable_supplies", readable);
        return result;
    }

    private static JSONObject compareBatterySources(JSONObject sample) throws Exception {
        JSONObject comparison = new JSONObject();
        JSONObject broadcast = sample.optJSONObject("broadcast");
        JSONObject properties = sample.optJSONObject("properties");
        int broadcastPercent = broadcast == null ? -1 : broadcast.optInt("percent", -1);
        int managerPercent = properties == null ? -1
                : properties.optInt("capacity_percent", -1);
        if (broadcastPercent >= 0 && broadcastPercent <= 100) {
            comparison.put("broadcast_percent", broadcastPercent);
        }
        if (managerPercent >= 0 && managerPercent <= 100) {
            comparison.put("manager_percent", managerPercent);
        }
        int sysfsPercent = -1;
        String sysfsName = "";
        JSONObject probe = sample.optJSONObject("dt50_sysfs_probe");
        if (probe != null) {
            JSONArray names = probe.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String name = names.optString(i);
                    JSONObject supply = probe.optJSONObject(name);
                    if (supply == null) continue;
                    try {
                        int level = Integer.parseInt(supply.optString("capacity", "").trim());
                        if (level >= 0 && level <= 100) {
                            sysfsPercent = level;
                            sysfsName = name;
                            break;
                        }
                    } catch (NumberFormatException ignored) { }
                }
            }
        }
        if (sysfsPercent >= 0) {
            comparison.put("sysfs_percent", sysfsPercent);
            comparison.put("sysfs_supply", sysfsName);
        }
        boolean disagreement = (broadcastPercent >= 0 && managerPercent >= 0
                && Math.abs(broadcastPercent - managerPercent) > 2)
                || (broadcastPercent >= 0 && sysfsPercent >= 0
                && Math.abs(broadcastPercent - sysfsPercent) > 2)
                || (managerPercent >= 0 && sysfsPercent >= 0
                && Math.abs(managerPercent - sysfsPercent) > 2);
        comparison.put("sources_disagree", disagreement);
        comparison.put("independent_accuracy_verified", false);
        return comparison;
    }

    private static JSONObject trackDt50BatteryTrend(
            Context context, JSONObject sample, long now) throws Exception {
        JSONObject trend = new JSONObject();
        JSONObject broadcast = sample.optJSONObject("broadcast");
        if (broadcast == null) {
            trend.put("state", "broadcast_unavailable");
            return trend;
        }
        int level = broadcast.optInt("percent", -1);
        int voltage = broadcast.optInt("voltage_mv", -1);
        int plugged = broadcast.optInt("plugged", -1);
        if (level < 0 || level > 100) {
            trend.put("state", "level_unavailable");
            return trend;
        }
        SharedPreferences prefs = diagPrefs(context);
        int priorLevel = prefs.getInt("dt50_probe_level", -1);
        long since = prefs.getLong("dt50_probe_since", 0L);
        int firstVoltage = prefs.getInt("dt50_probe_voltage_mv", -1);
        int voltageMin = prefs.getInt("dt50_probe_voltage_min_mv", -1);
        int voltageMax = prefs.getInt("dt50_probe_voltage_max_mv", -1);
        JSONObject properties = sample.optJSONObject("properties");
        int counter = properties == null ? -1
                : properties.optInt("charge_counter_uah", -1);
        int firstCounter = prefs.getInt("dt50_probe_counter_uah", -1);
        int firstPlugged = prefs.getInt("dt50_probe_plugged", -1);
        boolean plugChanged = prefs.getBoolean("dt50_probe_plug_changed", false);
        if (level != priorLevel || since <= 0L || since > now) {
            since = now;
            firstVoltage = voltage;
            voltageMin = voltage;
            voltageMax = voltage;
            firstCounter = counter;
            firstPlugged = plugged;
            plugChanged = false;
        } else if (plugged >= 0 && firstPlugged >= 0 && plugged != firstPlugged) {
            plugChanged = true;
        }
        if (voltage > 0) {
            voltageMin = voltageMin > 0 ? Math.min(voltageMin, voltage) : voltage;
            voltageMax = voltageMax > 0 ? Math.max(voltageMax, voltage) : voltage;
        }
        prefs.edit().putInt("dt50_probe_level", level)
                .putLong("dt50_probe_since", since)
                .putInt("dt50_probe_voltage_mv", firstVoltage)
                .putInt("dt50_probe_voltage_min_mv", voltageMin)
                .putInt("dt50_probe_voltage_max_mv", voltageMax)
                .putInt("dt50_probe_counter_uah", firstCounter)
                .putInt("dt50_probe_plugged", firstPlugged)
                .putBoolean("dt50_probe_plug_changed", plugChanged)
                .apply();

        long unchangedMinutes = Math.max(0L, (now - since) / 60000L);
        trend.put("reported_level_percent", level);
        trend.put("reported_52_percent", level == 52);
        trend.put("unchanged_minutes", unchangedMinutes);
        if (voltageMin > 0 && voltageMax >= voltageMin) {
            trend.put("voltage_min_mv", voltageMin);
            trend.put("voltage_max_mv", voltageMax);
            trend.put("voltage_span_mv", voltageMax - voltageMin);
        }
        if (counter >= 0 && firstCounter >= 0) {
            trend.put("charge_counter_delta_uah", (long) counter - firstCounter);
        }
        trend.put("plug_state_changed_while_level_unchanged", plugChanged);
        int voltageDelta = voltage > 0 && firstVoltage > 0
                ? voltage - firstVoltage : Integer.MIN_VALUE;
        if (voltageDelta != Integer.MIN_VALUE) {
            trend.put("voltage_delta_mv", voltageDelta);
        }
        boolean suspicious = unchangedMinutes >= 60
                && (plugChanged || (voltageMin > 0 && voltageMax > 0
                && voltageMax - voltageMin >= 150)
                || (counter >= 0 && firstCounter >= 0
                && Math.abs((long) counter - firstCounter) >= 50000));
        trend.put("possible_stale_percentage", suspicious);
        trend.put("estimated_actual_percentage", JSONObject.NULL);
        return trend;
    }

    private static String readSmallFile(File file, int max) {
        if (file == null || !file.isFile() || !file.canRead()) return "";
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[Math.max(32, Math.min(max, 512))];
            int read = input.read(buffer);
            if (read <= 0) return "";
            return safeText(new String(buffer, 0, read, StandardCharsets.UTF_8), max);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String readCommand(String[] command, int maxChars) {
        // Limit diagnostics so a vendor command cannot block the upload queue forever.
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean completed = process.waitFor(1800, TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                return "";
            }
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (out.length() < maxChars && (line = reader.readLine()) != null) {
                    if (line.toLowerCase(Locale.US).contains("serial")) continue;
                    out.append(line).append('\n');
                }
            }
            return safeText(out.toString(), maxChars);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static boolean readGlobalFlag(Context context, String key) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), key, 0) == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void appendEventSync(
            Context context, String event, JSONObject data, boolean critical) {
        synchronized (FILE_LOCK) {
            try {
                File file = activeLogFile(context);
                long limit = critical ? MAX_CRITICAL_LOG_BYTES : MAX_REGULAR_LOG_BYTES;
                if (file.length() >= limit) {
                    incrementDropped(context, critical ? "critical" : "sample");
                    return;
                }

                JSONObject line = new JSONObject();
                line.put("ts", isoNow());
                line.put("uptime_ms", SystemClock.elapsedRealtime());
                line.put("event", safeText(event, 80));
                line.put("data", data == null ? new JSONObject() : data);

                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (FileOutputStream stream = new FileOutputStream(file, true);
                     BufferedWriter writer = new BufferedWriter(
                             new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
                    writer.write(line.toString());
                    writer.newLine();
                    writer.flush();
                    if (critical) stream.getFD().sync();
                    String path = file.getAbsolutePath();
                    SEGMENT_COUNTS.put(path, SEGMENT_COUNTS.containsKey(path)
                            ? SEGMENT_COUNTS.get(path) + 1 : 1);
                }
            } catch (Throwable ignored) { }
        }
    }

    private static File activeLogFile(Context context) {
        // The same clock-time segmentation as the upload alarm, but split
        // busy periods into <=140-line parts. Never archive only the first
        // 180 lines and then delete a log containing untransmitted events.
        Calendar clock = Calendar.getInstance(VN_TZ);
        String today = dateKey(clock.getTime());
        String key = Prefs.getRegistryDeviceKey(context);
        if (key == null || key.isEmpty()) key = "unregistered-" + Build.MODEL;
        int minute = clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE);
        String slot;
        String date = today;
        if (minute < slotMinute(key, today, 13 * 60 + 30)) {
            slot = "s1330";
        } else if (minute < slotMinute(key, today, 21 * 60 + 30)) {
            slot = "s2130";
        } else {
            clock.add(Calendar.DAY_OF_YEAR, 1);
            date = dateKey(clock.getTime());
            slot = "s1330";
        }
        File dir = logDir(context);
        for (int part = 0; part < 1000; part++) {
            String suffix = String.format(Locale.US, "-%s-p%03d%s", slot, part, FILE_SUFFIX);
            File file = new File(dir, FILE_PREFIX + date + suffix);
            if (closedMarker(file).exists() || sentMarker(file).exists()) continue;
            if (file.length() >= MAX_SEGMENT_BYTES || logLineCount(file) >= MAX_SEGMENT_EVENTS) {
                closeForUpload(file);
                continue;
            }
            return file;
        }
        // Never overwrite/append a completed segment on an overloaded PDA.
        throw new IllegalStateException("LAUNCHER_LOG_SEGMENT_CAP_REACHED");
    }

    private static int logLineCount(File file) {
        String path = file.getAbsolutePath();
        Integer cached = SEGMENT_COUNTS.get(path);
        if (cached != null) return cached;
        int count = 0;
        if (file.isFile()) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) count++;
            } catch (Throwable ignored) { }
        }
        SEGMENT_COUNTS.put(path, count);
        return count;
    }

    private static void incrementDropped(Context context, String kind) {
        SharedPreferences prefs = diagPrefs(context);
        String key = "dropped_" + vietnamDateKey() + "_" + kind;
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply();
    }

    private static void maybeUploadPending(Context context) {
        IO.execute(() -> uploadPendingSync(context));
    }

    // Local-only diagnostic state: never stores payloads, identifiers or credentials.
    private static void noteUploadStatus(Context context, int httpStatus, String code) {
        try {
            diagPrefs(context).edit()
                    .putLong("upload_last_attempt_at", System.currentTimeMillis())
                    .putInt("upload_last_http_status", httpStatus)
                    .putString("upload_last_result", safeText(code, 80))
                    .apply();
        } catch (Throwable ignored) { }
    }

    static String uploadStatusText(Context context) {
        SharedPreferences prefs = diagPrefs(context);
        File[] pending = logDir(context).listFiles((dir, name) ->
                name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX));
        int files = pending == null ? 0 : pending.length;
        int buffered = 0;
        if (pending != null) {
            for (File file : pending) {
                if (bufferedMarker(file).exists()) buffered++;
            }
        }
        String key = Prefs.getRegistryDeviceKey(context);
        boolean validKey = key != null && key.matches("[a-fA-F0-9]{64}");
        long last = prefs.getLong("upload_last_attempt_at", 0L);
        String stamp = last <= 0 ? "Chưa có lần thử" :
                new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(new Date(last));
        return "Định danh: " + (validKey ? "Đã có DeviceKey" : "Thiếu DeviceKey") +
                "\nLog còn trên máy: " + files +
                "\nĐang chờ Drive: " + buffered +
                "\nLần thử gần nhất: " + stamp +
                "\nHTTP: " + prefs.getInt("upload_last_http_status", 0) +
                "\nKết quả: " + prefs.getString("upload_last_result", "Chưa có dữ liệu") +
                "\nLịch gửi: 13:15–13:45 và 21:15–21:45 (giờ VN)." +
                "\nChỉ xoá log khi Drive xác nhận thành công.";
    }

    private static void uploadPendingSync(Context context) {
        if (!UPLOAD_IN_FLIGHT.compareAndSet(false, true)) return;
        try {
            String deviceKey = Prefs.getRegistryDeviceKey(context);
            if (deviceKey == null || !deviceKey.matches("[a-fA-F0-9]{64}")) {
                noteUploadStatus(context, 0, "DEVICE_KEY_NOT_READY");
                return;
            }
            if (!networkUsable(context)) {
                noteUploadStatus(context, 0, "NETWORK_NOT_READY");
                return;
            }

            File[] files = logDir(context).listFiles((dir, name) ->
                    name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX));
            if (files == null || files.length == 0) {
                noteUploadStatus(context, 0, "NO_LOCAL_LOGS");
                return;
            }
            Arrays.sort(files, Comparator.comparing(File::getName));

            int uploaded = 0;
            for (File file : files) {
                if (uploaded >= 3) break;
                if (sentMarker(file).exists()) continue;
                String date = dateFromFile(file);
                if (date.isEmpty()) continue;
                if (!isUploadDue(deviceKey, file)) continue;
                if (!retryDue(file)) continue;

                closeForUpload(file);
                int result = UPLOAD_FAILED;
                File buffered = bufferedMarker(file);
                if (buffered.isFile()) {
                    result = checkDriveArchiveReceipt(file, deviceKey);
                    if (result == RECEIPT_MISSING) {
                        // The server no longer has the buffer (e.g. retention).
                        // Resend once with the same immutable, closed file.
                        buffered.delete();
                    } else if (result == UPLOAD_DRIVE_SYNCED) {
                        deleteAfterDriveReceipt(file);
                        uploaded++;
                    } else if (result == UPLOAD_BUFFERED) {
                        // No repetitive POST and no frequent status polling:
                        // check again at the next pre-existing upload window.
                        clearRetry(file);
                    } else {
                        scheduleRetry(file);
                    }
                    if (result != RECEIPT_MISSING) {
                        if (result == UPLOAD_DRIVE_SYNCED) {
                            noteUploadStatus(context, 200, "DRIVE_SYNCED_RECEIPT");
                            scheduleNextQueuedFile(context, files, file, deviceKey);
                        } else if (result == UPLOAD_BUFFERED) {
                            noteUploadStatus(context, 202, "SERVER_BUFFERED_NOT_DRIVE");
                        } else {
                            noteUploadStatus(context, 0, "DRIVE_RECEIPT_CHECK_FAILED");
                        }
                        break;
                    }
                }

                result = uploadFile(context, file, deviceKey, date);
                if (result == UPLOAD_DRIVE_SYNCED) {
                    deleteAfterDriveReceipt(file);
                    uploaded++;
                    scheduleNextQueuedFile(context, files, file, deviceKey);
                } else if (result == UPLOAD_BUFFERED) {
                    // Durable server buffer received but the Google Drive copy
                    // may not exist. Persist receipt identity; keep the data.
                    try {
                        String bundleId = bundleIdFor(file, deviceKey, date);
                        markBuffered(file, bundleId);
                        clearRetry(file);
                    } catch (Throwable ignored) {
                        scheduleRetry(file);
                    }
                } else {
                    scheduleRetry(file);
                    scheduleDeferredDrain(context, nextRetryDelay(file));
                }
                break;
            }
        } finally {
            UPLOAD_IN_FLIGHT.set(false);
        }
    }

    private static int uploadFile(
            Context context, File file, String deviceKey, String date) {
        HttpURLConnection connection = null;
        try {
            JSONArray events = new JSONArray();
            int totalLines = 0;
            int invalidLines = 0;
            String firstTs = "";
            String lastTs = "";

            synchronized (FILE_LOCK) {
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        totalLines++;
                        try {
                            JSONObject event = new JSONObject(line);
                            String ts = event.optString("ts", "");
                            if (firstTs.isEmpty()) firstTs = ts;
                            lastTs = ts;
                            if (events.length() >= MAX_UPLOAD_EVENTS) {
                                // Never silently archive a partial segment and
                                // delete its untransmitted records.
                                return UPLOAD_FAILED;
                            }
                            events.put(event);
                        } catch (Throwable ignored) {
                            invalidLines++;
                        }
                    }
                }
            }

            if (invalidLines > 0 || events.length() != totalLines) {
                // Invalid JSONL remains on-device for repair/manual analysis.
                return UPLOAD_FAILED;
            }
            if (events.length() == 0) {
                // Even a damaged/non-parsable log must be retained until
                // its contents can be inspected or repaired.
                return UPLOAD_FAILED;
            }

            String suffix = file.getName().contains("-s1330") ? "s1330"
                    : file.getName().contains("-s2130") ? "s2130"
                    : file.getName().contains("-late") ? "late" : "main";
            JSONObject summary = new JSONObject();
            summary.put("event_count_uploaded", events.length());
            summary.put("event_count_total", totalLines);
            summary.put("event_count_omitted", Math.max(0, totalLines - invalidLines - events.length()));
            summary.put("invalid_line_count", invalidLines);
            summary.put("first_event_at", firstTs);
            summary.put("last_event_at", lastTs);
            summary.put("local_file_bytes", file.length());
            summary.put("dropped_sample_count",
                    diagPrefs(context).getInt("dropped_" + date + "_sample", 0));
            summary.put("dropped_critical_count",
                    diagPrefs(context).getInt("dropped_" + date + "_critical", 0));

            JSONObject payload = new JSONObject();
            payload.put("date", date);
            payload.put("upload_window", uploadWindow(deviceKey, date, suffix));
            payload.put("summary", summary);
            payload.put("events", events);

            JSONObject device = new JSONObject();
            device.put("device_key", deviceKey.toLowerCase(Locale.US));
            device.put("manufacturer", safeText(Build.MANUFACTURER, 80));
            device.put("brand", safeText(Build.BRAND, 80));
            device.put("model", safeText(Build.MODEL, 100));
            device.put("device", safeText(Build.DEVICE, 100));
            device.put("product", safeText(Build.PRODUCT, 100));
            device.put("hardware", safeText(Build.HARDWARE, 100));
            device.put("sdk_int", Build.VERSION.SDK_INT);
            device.put("android_version", safeText(Build.VERSION.RELEASE, 40));
            device.put("package", BuildConfig.APPLICATION_ID);
            device.put("version_name", BuildConfig.VERSION_NAME);
            device.put("version_code", BuildConfig.VERSION_CODE);

            JSONObject body = new JSONObject();
            body.put("schema", "supra-launcher-log-v1");
            body.put("generated_at", lastTs.isEmpty() ? isoNow() : lastTs);
            body.put("severity", "INFO");
            body.put("reason", suffix.equals("late")
                    ? "launcher_daily_diagnostic_late"
                    : "launcher_daily_diagnostic");
            body.put("bundle_id", bundleIdFor(file, deviceKey, date));
            body.put("boundary_id",
                    "launcher:" + deviceKey.substring(0, 16).toLowerCase(Locale.US)
                            + ":" + date + ":" + suffix);
            body.put("trace_id", "launcher-" + date + "-" + suffix);
            body.put("device", device);
            body.put("payload", payload);

            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 155_000) return UPLOAD_FAILED;

            connection = (HttpURLConnection) new URL(API_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("x-supra-launcher-log-version", "1");
            connection.setRequestProperty(
                    "User-Agent", "SUPRA-PDA-Launcher/" + BuildConfig.VERSION_NAME);
            connection.setFixedLengthStreamingMode(bytes.length);

            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
                output.flush();
            }

            int code = connection.getResponseCode();
            if (code != 200 && code != 202) {
                String reason = "HTTP_" + code;
                try (java.io.InputStream errorStream = connection.getErrorStream()) {
                    if (errorStream != null) {
                        byte[] raw = new byte[512];
                        int length = errorStream.read(raw);
                        if (length > 0) {
                            String value = new String(raw, 0, length, StandardCharsets.UTF_8);
                            String error = new JSONObject(value).optString("error", "");
                            if (error.matches("[A-Z0-9_]{3,70}")) reason += "_" + error;
                        }
                    }
                } catch (Throwable ignored) { }
                noteUploadStatus(context, code, reason);
                return UPLOAD_FAILED;
            }
            JSONObject reply;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null && data.length() < 4096) data.append(line);
                reply = new JSONObject(data.toString());
            }
            if (reply.optBoolean("archived", false)
                    && "DRIVE_SYNCED".equals(reply.optString("archive_status", ""))) {
                noteUploadStatus(context, code, "DRIVE_SYNCED");
                return UPLOAD_DRIVE_SYNCED;
            }
            if ("BUFFERED".equals(reply.optString("status", ""))) {
                noteUploadStatus(context, code, "SERVER_BUFFERED_NOT_DRIVE");
                return UPLOAD_BUFFERED;
            }
            noteUploadStatus(context, code, "INVALID_SERVER_RECEIPT");
            return UPLOAD_FAILED;
        } catch (Throwable error) {
            noteUploadStatus(context, 0, "UPLOAD_EXCEPTION_" + error.getClass().getSimpleName());
            return UPLOAD_FAILED;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String bundleIdFor(File file, String deviceKey, String date) throws Exception {
        String name = file.getName();
        String suffix = name.contains("-s1330") ? "s1330"
                : name.contains("-s2130") ? "s2130"
                : name.contains("-late") ? "late" : "main";
        String unique = file.getName().matches(".*-p[0-9]{3}\\.jsonl$")
                ? file.getName() : suffix;
        return sha256Hex(deviceKey + "|" + date + "|" + unique + "|" + file.length())
                .substring(0, 32);
    }

    private static void scheduleNextQueuedFile(
            Context context, File[] files, File completed, String deviceKey) {
        for (File candidate : files) {
            if (!candidate.equals(completed)
                    && !sentMarker(candidate).exists()
                    && isUploadDue(deviceKey, candidate)) {
                // Keep server's existing 30-second device throttle intact.
                scheduleDeferredDrain(context, 45_000L);
                return;
            }
        }
    }

    private static File bufferedMarker(File file) {
        return new File(file.getParentFile(), file.getName() + ".buffered");
    }

    private static void markBuffered(File file, String bundleId) {
        synchronized (FILE_LOCK) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(bufferedMarker(file), false))) {
                writer.write(bundleId);
            } catch (Throwable ignored) { }
        }
    }

    private static int checkDriveArchiveReceipt(File file, String deviceKey) {
        HttpURLConnection connection = null;
        try {
            String bundleId;
            try (BufferedReader reader = new BufferedReader(new FileReader(bufferedMarker(file)))) {
                bundleId = reader.readLine();
            }
            if (bundleId == null || !bundleId.matches("[0-9a-f]{32,64}")) return RECEIPT_MISSING;
            String url = API_URL + "/status?device_key=" + deviceKey + "&bundle_id=" + bundleId;
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(9000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("x-supra-launcher-log-version", "1");
            int code = connection.getResponseCode();
            if (code == 404) return RECEIPT_MISSING;
            if (code != 200) return UPLOAD_FAILED;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder raw = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null && raw.length() < 4096) raw.append(line);
                JSONObject receipt = new JSONObject(raw.toString());
                return receipt.optBoolean("archived", false)
                        && "DRIVE_SYNCED".equals(receipt.optString("status", ""))
                        ? UPLOAD_DRIVE_SYNCED : UPLOAD_BUFFERED;
            }
        } catch (Throwable ignored) {
            return UPLOAD_FAILED;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void deleteAfterDriveReceipt(File file) {
        // Write local receipt before deletion so interruption cannot
        // accidentally cause a second full upload.
        synchronized (FILE_LOCK) {
            markSent(file);
            if (file.delete() || !file.exists()) {
                SEGMENT_COUNTS.remove(file.getAbsolutePath());
                clearRetry(file);
                closedMarker(file).delete();
                bufferedMarker(file).delete();
                sentMarker(file).delete();
            }
        }
    }

    private static long nextRetryDelay(File file) {
        File marker = retryMarker(file);
        try (BufferedReader reader = new BufferedReader(new FileReader(marker))) {
            String[] parts = reader.readLine().split(",");
            return Math.max(60_000L, Long.parseLong(parts[1]) - System.currentTimeMillis());
        } catch (Throwable ignored) {
            return 15L * 60L * 1000L;
        }
    }

    private static boolean isUploadDue(String deviceKey, File file) {
        String date = dateFromFile(file);
        String today = vietnamDateKey();
        int comparison = date.compareTo(today);
        if (comparison < 0) return true;
        if (comparison > 0) return false;
        // Never send an unfinished segment early, even on Launcher relaunch.
        String filename = file.getName();
        if (filename.contains("-late")) return false; // Legacy late logs: tomorrow.
        int base = filename.contains("-s1330") ? 13 * 60 + 30 : 21 * 60 + 30;
        Calendar calendar = Calendar.getInstance(VN_TZ);
        int nowMinute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
        return nowMinute >= slotMinute(deviceKey, today, base);
    }

    private static String uploadWindow(String deviceKey, String date, String suffix) {
        int base = "s1330".equals(suffix) ? 13 * 60 + 30 : 21 * 60 + 30;
        int minute = slotMinute(deviceKey, date, base);
        return String.format(Locale.US, "%02d:%02d Asia/Ho_Chi_Minh",
                minute / 60, minute % 60);
    }

    private static boolean networkUsable(Context context) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network network = cm.getActiveNetwork();
            NetworkCapabilities caps =
                    network == null ? null : cm.getNetworkCapabilities(network);
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean retryDue(File file) {
        File retry = retryMarker(file);
        if (!retry.isFile()) return true;
        try (BufferedReader reader = new BufferedReader(new FileReader(retry))) {
            String raw = reader.readLine();
            if (raw == null) return true;
            String[] parts = raw.trim().split(",");
            if (parts.length != 2) return true;
            long next = Long.parseLong(parts[1]);
            return System.currentTimeMillis() >= next;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void scheduleRetry(File file) {
        File retry = retryMarker(file);
        int attempt = 0;
        if (retry.isFile()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(retry))) {
                String raw = reader.readLine();
                if (raw != null) {
                    String[] parts = raw.trim().split(",");
                    if (parts.length > 0) attempt = Integer.parseInt(parts[0]);
                }
            } catch (Throwable ignored) { }
        }
        int index = Math.min(Math.max(0, attempt), RETRY_DELAYS_MS.length - 1);
        long next = System.currentTimeMillis() + RETRY_DELAYS_MS[index];
        int nextAttempt = Math.min(attempt + 1, RETRY_DELAYS_MS.length - 1);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(retry, false))) {
            writer.write(nextAttempt + "," + next);
        } catch (Throwable ignored) { }
    }

    private static void clearRetry(File file) {
        try { retryMarker(file).delete(); } catch (Throwable ignored) { }
    }

    private static void markSent(File file) {
        try (BufferedWriter writer =
                     new BufferedWriter(new FileWriter(sentMarker(file), false))) {
            writer.write(isoNow());
        } catch (Throwable ignored) { }
    }

    private static File sentMarker(File file) {
        return new File(file.getParentFile(), file.getName() + ".sent");
    }

    private static File retryMarker(File file) {
        return new File(file.getParentFile(), file.getName() + ".retry");
    }

    private static File closedMarker(File file) {
        return new File(file.getParentFile(), file.getName() + ".closed");
    }

    private static void closeForUpload(File file) {
        if (file.getName().contains("-late")) return;
        if (closedMarker(file).exists()) return;
        try (BufferedWriter writer =
                     new BufferedWriter(new FileWriter(closedMarker(file), false))) {
            writer.write(isoNow());
        } catch (Throwable ignored) { }
    }

    private static String dateFromFile(File file) {
        String name = file.getName();
        if (!name.startsWith(FILE_PREFIX) || name.length() < FILE_PREFIX.length() + 10) {
            return "";
        }
        String date = name.substring(FILE_PREFIX.length(), FILE_PREFIX.length() + 10);
        return date.matches("\\d{4}-\\d{2}-\\d{2}") ? date : "";
    }

    private static void cleanupOldFiles(Context context) {
        File[] files = logDir(context).listFiles();
        if (files == null) return;
        final String deviceKey = Prefs.getRegistryDeviceKey(context);
        final long staleMarkerCutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L;
        for (File file : files) {
            try {
                String name = file.getName();
                if (name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)) {
                    // Old v0.3.24 clients mistakenly wrote .sent for HTTP 200
                    // even when Drive was DEFERRED. Revalidate historical logs
                    // that still exist instead of discarding them.
                    if (sentMarker(file).exists()
                            && !name.contains("-s1330")
                            && !name.contains("-s2130")
                            && deviceKey != null && deviceKey.matches("[a-fA-F0-9]{64}")) {
                        markBuffered(file, bundleIdFor(file, deviceKey, dateFromFile(file)));
                        sentMarker(file).delete();
                    }
                    if (sentMarker(file).exists()) {
                        // Already Drive-verified on v0.3.25. Complete a previous
                        // interrupted deletion; never clear .sent if file remains.
                        deleteAfterDriveReceipt(file);
                    }
                } else if ((name.endsWith(".retry") || name.endsWith(".closed")
                        || name.endsWith(".buffered") || name.endsWith(".sent"))
                        && file.lastModified() < staleMarkerCutoff) {
                    String suffix = name.substring(name.lastIndexOf('.'));
                    File parentLog = new File(file.getParentFile(),
                            name.substring(0, name.length() - suffix.length()));
                    if (!parentLog.exists()) file.delete();
                }
                // NEVER auto-delete an unsent JSONL because it is 14 days old.
                // If Drive is unavailable, data must survive until confirmed.
            } catch (Throwable ignored) { }
        }
    }

    private static File logDir(Context context) {
        File dir = new File(context.getFilesDir(), LOG_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static SharedPreferences diagPrefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void scheduleJob(Context context) {
        try {
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) return;
            JobInfo existing = scheduler.getPendingJob(JOB_ID);
            if (!isDt50()) {
                if (existing != null) scheduler.cancel(JOB_ID);
                return;
            }
            // Replace the legacy 15-minute periodic sampling with a DT50-only
            // 3-hour JobScheduler sample; Android can defer this to save power.
            if (existing != null && existing.getIntervalMillis() == JOB_INTERVAL_MS) return;
            if (existing != null) scheduler.cancel(JOB_ID);
            scheduler.schedule(new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, LauncherDiagnosticJobService.class))
                    .setPeriodic(JOB_INTERVAL_MS)
                    .setPersisted(true)
                    .build());
        } catch (Throwable ignored) { }
    }

    private static void installCrashHandler(Context context) {
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof LauncherCrashHandler) return;
        Thread.setDefaultUncaughtExceptionHandler(
                new LauncherCrashHandler(context.getApplicationContext(), previous));
    }

    private static final class LauncherCrashHandler
            implements Thread.UncaughtExceptionHandler {
        private final Context context;
        private final Thread.UncaughtExceptionHandler previous;

        LauncherCrashHandler(Context context, Thread.UncaughtExceptionHandler previous) {
            this.context = context;
            this.previous = previous;
        }

        @Override public void uncaughtException(Thread thread, Throwable throwable) {
            try {
                JSONObject data = new JSONObject();
                data.put("thread", safeText(thread == null ? "" : thread.getName(), 80));
                data.put("exception_class",
                        throwable == null ? "" : throwable.getClass().getName());
                JSONArray frames = new JSONArray();
                if (throwable != null) {
                    StackTraceElement[] stack = throwable.getStackTrace();
                    for (int i = 0; i < Math.min(stack.length, 40); i++) {
                        StackTraceElement frame = stack[i];
                        JSONObject row = new JSONObject();
                        row.put("class", safeText(frame.getClassName(), 160));
                        row.put("method", safeText(frame.getMethodName(), 120));
                        row.put("file", safeText(frame.getFileName(), 120));
                        row.put("line", frame.getLineNumber());
                        frames.put(row);
                    }
                }
                data.put("stack", frames);
                appendEventSync(context, "uncaught_crash", data, true);
            } catch (Throwable ignored) { }

            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        }
    }

    private static String vietnamDateKey() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(VN_TZ);
        return format.format(new Date());
    }

    private static String isoNow() {
        SimpleDateFormat format =
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static String sha256Hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return out.toString();
    }

    private static String safeText(Object value, int max) {
        if (value == null) return "";
        String text = String.valueOf(value)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .trim();
        if (text.length() > max) return text.substring(0, max);
        return text;
    }
}
