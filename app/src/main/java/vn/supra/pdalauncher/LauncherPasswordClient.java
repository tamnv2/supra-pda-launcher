package vn.supra.pdalauncher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class LauncherPasswordClient {
    private static final String API_BASE = "https://inventory-beta.supra.cc.cd";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Callback {
        void onResult(Result result);
    }

    static final class Result {
        final boolean requestOk;
        final boolean valid;
        final String error;
        final String message;
        final String operationalDate;
        final String validUntilVn;
        final int resetCooldownSeconds;
        final int retryAfterSeconds;

        Result(
                boolean requestOk,
                boolean valid,
                String error,
                String message,
                String operationalDate,
                String validUntilVn,
                int resetCooldownSeconds,
                int retryAfterSeconds) {
            this.requestOk = requestOk;
            this.valid = valid;
            this.error = error == null ? "" : error;
            this.message = message == null ? "" : message;
            this.operationalDate = operationalDate == null ? "" : operationalDate;
            this.validUntilVn = validUntilVn == null ? "" : validUntilVn;
            this.resetCooldownSeconds = Math.max(0, resetCooldownSeconds);
            this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
        }
    }

    private LauncherPasswordClient() {}

    static void status(Context context, Callback callback) {
        execute(context, "GET", "/api/launcher/password/status", null, callback);
    }

    static void verify(Context context, String code, Callback callback) {
        try {
            JSONObject body = new JSONObject();
            body.put("code", code == null ? "" : code.trim());
            execute(context, "POST", "/api/launcher/password/verify", body, callback);
        } catch (Exception e) {
            deliver(callback, failure("CLIENT_JSON_ERROR"));
        }
    }

    static void reset(Context context, Callback callback) {
        execute(context, "POST", "/api/launcher/password/reset", new JSONObject(), callback);
    }

    private static void execute(
            Context context,
            String method,
            String path,
            JSONObject body,
            Callback callback) {
        final Context app = context.getApplicationContext();
        final JSONObject suppliedBody = body;
        new Thread(() -> {
            String deviceKey = DeviceRegistryClient.currentDeviceKey(app);
            if (deviceKey.isEmpty()) {
                DeviceRegistryClient.syncIfNeeded(app);
                deliver(callback, failure("PDA_NOT_REGISTERED"));
                return;
            }

            HttpURLConnection connection = null;
            try {
                JSONObject requestBody = suppliedBody == null ? new JSONObject() : suppliedBody;
                String endpoint = API_BASE + path;
                if ("GET".equals(method)) {
                    endpoint += "?device_key=" + deviceKey;
                } else {
                    requestBody.put("device_key", deviceKey);
                }

                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setRequestMethod(method);
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(9000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent",
                        "SUPRA-PDA-Launcher/" + BuildConfig.VERSION_NAME);

                if (!"GET".equals(method)) {
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    byte[] bytes = requestBody.toString().getBytes(StandardCharsets.UTF_8);
                    connection.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(bytes);
                        output.flush();
                    }
                }

                int status = connection.getResponseCode();
                JSONObject payload = new JSONObject(readBody(connection, status));
                Result result = new Result(
                        status >= 200 && status < 300,
                        payload.optBoolean("valid", false),
                        payload.optString("error", ""),
                        payload.optString("message", ""),
                        payload.optString("operational_date", ""),
                        payload.optString("valid_until_vn", ""),
                        payload.optInt("reset_cooldown_seconds", 0),
                        payload.optInt("retry_after_seconds", 0));
                deliver(callback, result);
            } catch (Throwable error) {
                deliver(callback, failure(error.getClass().getSimpleName()));
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "supra-launcher-password").start();
    }

    private static String readBody(HttpURLConnection connection, int status) throws Exception {
        InputStream stream = status >= 200 && status < 400
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (stream == null) return "{}";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        String line;
        int size = 0;
        while ((line = reader.readLine()) != null && size < 16_384) {
            builder.append(line);
            size += line.length();
        }
        reader.close();
        return builder.length() == 0 ? "{}" : builder.toString();
    }

    private static Result failure(String error) {
        return new Result(false, false, error, "", "", "", 0, 0);
    }

    private static void deliver(Callback callback, Result result) {
        if (callback == null) return;
        MAIN.post(() -> callback.onResult(result));
    }
}
