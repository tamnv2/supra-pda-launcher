package vn.supra.pdalauncher;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.os.StatFs;
import android.util.DisplayMetrics;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

final class DeviceRegistryClient {
    private static final String API_BASE = "https://inventory-beta.supra.cc.cd";
    private static final int SCHEMA_VERSION = 1;
    private static final long VALIDATION_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long[] RETRY_DELAYS_MS = new long[] {
            30L * 1000L,
            2L * 60L * 1000L,
            5L * 60L * 1000L,
            15L * 60L * 1000L
    };
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    private static final AtomicBoolean RETRY_SCHEDULED = new AtomicBoolean(false);
    private static final Handler RETRY_HANDLER = new Handler(Looper.getMainLooper());

    private DeviceRegistryClient() {}

    static void retryPending(final Context context) {
        final Context app = context.getApplicationContext();
        if (Prefs.isRegistryRegistered(app)) return;

        long now = System.currentTimeMillis();
        long nextRetryAt = Prefs.getRegistryNextRetryAt(app);
        if (nextRetryAt > now) {
            scheduleRetry(app, nextRetryAt - now);
            return;
        }
        syncIfNeeded(app);
    }

    static void syncIfNeeded(final Context context) {
        final Context app = context.getApplicationContext();
        if (!IN_FLIGHT.compareAndSet(false, true)) return;

        new Thread(() -> {
            try {
                Snapshot snapshot = Snapshot.capture(app);
                if (snapshot == null) return;

                boolean registered = Prefs.isRegistryRegistered(app);
                boolean sameDevice = snapshot.deviceKey.equals(Prefs.getRegistryDeviceKey(app));
                boolean samePayload = snapshot.payloadHash.equals(Prefs.getRegistryPayloadHash(app));
                long now = System.currentTimeMillis();

                if (!registered) {
                    long nextRetryAt = Prefs.getRegistryNextRetryAt(app);
                    if (nextRetryAt > now) {
                        scheduleRetry(app, nextRetryAt - now);
                        return;
                    }
                }

                if (!registered || !sameDevice || !samePayload) {
                    register(app, snapshot, now);
                    return;
                }

                long lastValidated = Prefs.getRegistryLastValidatedAt(app);
                if (lastValidated <= 0L || now - lastValidated >= VALIDATION_INTERVAL_MS) {
                    validateOrRepair(app, snapshot, now);
                }
            } catch (Throwable ignored) {
                // Registry sync is best-effort and must never affect launcher availability.
            } finally {
                IN_FLIGHT.set(false);
            }
        }, "supra-pda-registry").start();
    }

    private static void validateOrRepair(Context context, Snapshot snapshot, long now) {
        Prefs.markRegistryAttempt(context, now);
        HttpURLConnection connection = null;
        try {
            URL url = new URL(API_BASE + "/api/pda/registry/status?device_key=" + snapshot.deviceKey);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", userAgent());

            int code = connection.getResponseCode();
            if (code != 200) return;
            JSONObject response = new JSONObject(readResponse(connection));
            if (response.optBoolean("registered", false)) {
                Prefs.markRegistryValidated(context, now);
                return;
            }
            Prefs.markRegistryMissing(context);
        } catch (Throwable ignored) {
            return;
        } finally {
            if (connection != null) connection.disconnect();
        }

        register(context, snapshot, System.currentTimeMillis());
    }

    private static void register(Context context, Snapshot snapshot, long now) {
        Prefs.markRegistryAttempt(context, now);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(API_BASE + "/api/pda/registry/register").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("User-Agent", userAgent());

            byte[] body = snapshot.toJson().toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
                output.flush();
            }

            int code = connection.getResponseCode();
            if (code != 200) {
                if (isRetryableHttp(code)) schedulePendingRetry(context, System.currentTimeMillis());
                return;
            }

            JSONObject response = new JSONObject(readResponse(connection));
            if (!response.optBoolean("registered", false)) {
                schedulePendingRetry(context, System.currentTimeMillis());
                return;
            }

            // Durable Object registration is not enough. Only mark success after
            // Google Sheet persistence is acknowledged by the service.
            if (!response.optBoolean("sheet_synced", false)) {
                schedulePendingRetry(context, System.currentTimeMillis());
                return;
            }

            Prefs.markRegistrySuccess(
                    context,
                    snapshot.deviceKey,
                    snapshot.payloadHash,
                    response.optInt("registry_schema_version", SCHEMA_VERSION),
                    System.currentTimeMillis());
        } catch (Throwable ignored) {
            schedulePendingRetry(context, System.currentTimeMillis());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean isRetryableHttp(int code) {
        return code == 408 || code == 425 || code == 429 || code >= 500;
    }

    private static void schedulePendingRetry(Context context, long now) {
        int step = Math.max(0, Prefs.getRegistryRetryStep(context));
        int index = Math.min(step, RETRY_DELAYS_MS.length - 1);
        long delay = RETRY_DELAYS_MS[index];
        int nextStep = Math.min(step + 1, RETRY_DELAYS_MS.length - 1);
        long nextRetryAt = now + delay;

        Prefs.markRegistryPending(context, nextStep, nextRetryAt, now);
        scheduleRetry(context, delay);
    }

    private static void scheduleRetry(Context context, long delayMs) {
        final Context app = context.getApplicationContext();
        if (!RETRY_SCHEDULED.compareAndSet(false, true)) return;

        RETRY_HANDLER.postDelayed(() -> {
            RETRY_SCHEDULED.set(false);
            retryPending(app);
        }, Math.max(1000L, delayMs));
    }

    private static String readResponse(HttpURLConnection connection) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        String line;
        int size = 0;
        while ((line = reader.readLine()) != null && size < 32_768) {
            builder.append(line);
            size += line.length();
        }
        reader.close();
        return builder.toString();
    }

    private static String userAgent() {
        return "SUPRA-PDA-Launcher/" + BuildConfig.VERSION_NAME;
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) hex.append(String.format(Locale.US, "%02x", item & 0xff));
        return hex.toString();
    }

    private static String value(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static int totalRamMb(Context context) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return 0;
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            return (int) Math.min(Integer.MAX_VALUE, info.totalMem / (1024L * 1024L));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int totalStorageMb() {
        try {
            StatFs stats = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            long bytes = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2
                    ? stats.getTotalBytes()
                    : stats.getBlockCountLong() * stats.getBlockSizeLong();
            return (int) Math.min(Integer.MAX_VALUE, bytes / (1024L * 1024L));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String screenResolution(Context context) {
        try {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            return metrics.widthPixels + "x" + metrics.heightPixels;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static final class Snapshot {
        final String deviceKey;
        final String payloadHash;
        final String primaryIdentifier;
        final String identifierSource;
        final String serialRaw;
        final String serialNormalized;
        final String imei1;
        final String androidId;
        final String manufacturer;
        final String brand;
        final String model;
        final String device;
        final String product;
        final String board;
        final String hardware;
        final String androidVersion;
        final int sdk;
        final String securityPatch;
        final String buildFingerprint;
        final String screenResolution;
        final int totalRamMb;
        final int totalStorageMb;

        private Snapshot(
                String deviceKey,
                String payloadHash,
                String primaryIdentifier,
                String identifierSource,
                String serialRaw,
                String serialNormalized,
                String imei1,
                String androidId,
                String manufacturer,
                String brand,
                String model,
                String device,
                String product,
                String board,
                String hardware,
                String androidVersion,
                int sdk,
                String securityPatch,
                String buildFingerprint,
                String screenResolution,
                int totalRamMb,
                int totalStorageMb) {
            this.deviceKey = deviceKey;
            this.payloadHash = payloadHash;
            this.primaryIdentifier = primaryIdentifier;
            this.identifierSource = identifierSource;
            this.serialRaw = serialRaw;
            this.serialNormalized = serialNormalized;
            this.imei1 = imei1;
            this.androidId = androidId;
            this.manufacturer = manufacturer;
            this.brand = brand;
            this.model = model;
            this.device = device;
            this.product = product;
            this.board = board;
            this.hardware = hardware;
            this.androidVersion = androidVersion;
            this.sdk = sdk;
            this.securityPatch = securityPatch;
            this.buildFingerprint = buildFingerprint;
            this.screenResolution = screenResolution;
            this.totalRamMb = totalRamMb;
            this.totalStorageMb = totalStorageMb;
        }

        static Snapshot capture(Context context) throws Exception {
            DeviceIdentifier.Result identity = DeviceIdentifier.resolve(context);
            if (!identity.isAvailable() || identity.normalized.length() < 4) return null;

            String source = value(identity.source).toUpperCase(Locale.US);
            String deviceKey = sha256(source + "|" + identity.normalized);

            String manufacturer = value(Build.MANUFACTURER);
            String brand = value(Build.BRAND);
            String model = value(Build.MODEL);
            String device = value(Build.DEVICE);
            String product = value(Build.PRODUCT);
            String board = value(Build.BOARD);
            String hardware = value(Build.HARDWARE);
            String androidVersion = value(Build.VERSION.RELEASE);
            int sdk = Build.VERSION.SDK_INT;
            String securityPatch = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? value(Build.VERSION.SECURITY_PATCH) : "";
            String buildFingerprint = value(Build.FINGERPRINT);
            String resolution = screenResolution(context);
            int ram = totalRamMb(context);
            int storage = totalStorageMb();

            String canonical = String.join("\n",
                    source,
                    identity.normalized,
                    value(identity.serialNormalized),
                    value(identity.imei1),
                    value(identity.androidId),
                    manufacturer,
                    brand,
                    model,
                    device,
                    product,
                    board,
                    hardware,
                    androidVersion,
                    String.valueOf(sdk),
                    securityPatch,
                    buildFingerprint,
                    resolution,
                    String.valueOf(ram),
                    String.valueOf(storage),
                    BuildConfig.VERSION_NAME,
                    String.valueOf(BuildConfig.VERSION_CODE),
                    String.valueOf(SCHEMA_VERSION));
            String payloadHash = sha256(canonical);

            return new Snapshot(
                    deviceKey,
                    payloadHash,
                    value(identity.value),
                    source,
                    value(identity.serialRaw),
                    value(identity.serialNormalized),
                    value(identity.imei1),
                    value(identity.androidId),
                    manufacturer,
                    brand,
                    model,
                    device,
                    product,
                    board,
                    hardware,
                    androidVersion,
                    sdk,
                    securityPatch,
                    buildFingerprint,
                    resolution,
                    ram,
                    storage);
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("device_key", deviceKey);
            json.put("primary_identifier", primaryIdentifier);
            json.put("identifier_source", identifierSource);
            json.put("serial_raw", serialRaw);
            json.put("serial_normalized", serialNormalized);
            json.put("imei1", imei1);
            json.put("android_id", androidId);
            json.put("manufacturer", manufacturer);
            json.put("brand", brand);
            json.put("model", model);
            json.put("device", device);
            json.put("product", product);
            json.put("board", board);
            json.put("hardware", hardware);
            json.put("android_version", androidVersion);
            json.put("sdk", sdk);
            json.put("security_patch", securityPatch);
            json.put("build_fingerprint", buildFingerprint);
            json.put("screen_resolution", screenResolution);
            json.put("total_ram_mb", totalRamMb);
            json.put("total_storage_mb", totalStorageMb);
            json.put("launcher_version", BuildConfig.VERSION_NAME);
            json.put("launcher_version_code", BuildConfig.VERSION_CODE);
            json.put("registry_schema_version", SCHEMA_VERSION);
            json.put("payload_hash", payloadHash);
            return json;
        }
    }
}
