package vn.supra.pdalauncher;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DeviceIdentifier {
    public static final class Result {
        public final String label;
        public final String value;
        public final String detail;

        Result(String label, String value, String detail) {
            this.label = label;
            this.value = value;
            this.detail = detail;
        }

        public boolean isAvailable() {
            return value != null && value.length() > 0;
        }
    }

    private static final Pattern MT90_SERIAL =
            Pattern.compile("(?i)(MT90[A-Z0-9]+)");

    private DeviceIdentifier() { }

    public static Result resolve(Context context) {
        String manufacturer = safe(Build.MANUFACTURER);
        String model = safe(Build.MODEL);
        String fingerprint = (manufacturer + " " + model).toLowerCase(Locale.US);

        if (fingerprint.contains("newland") || fingerprint.contains("mt90")) {
            String serial = readNewlandMt90Serial();
            return serial != null
                    ? new Result("S/N", serial, manufacturer + " " + model)
                    : new Result("S/N", null, "Không đọc được S/N • " + manufacturer + " " + model);
        }

        if (fingerprint.contains("urovo") || fingerprint.contains("dt50")) {
            String serial = readUrovoSerial();
            return serial != null
                    ? new Result("S/N", serial, manufacturer + " " + model)
                    : new Result("S/N", null, "Không đọc được S/N • " + manufacturer + " " + model);
        }

        String serial = readGenericSerial();
        return serial != null
                ? new Result("S/N", serial, manufacturer + " " + model)
                : new Result("S/N", null, "Không đọc được S/N • " + manufacturer + " " + model);
    }

    private static String readNewlandMt90Serial() {
        String raw = firstNonEmpty(
                systemProperty("vendor.gsm.serial"),
                shellGetprop("vendor.gsm.serial"));

        String serial = extractMt90Serial(raw);
        if (serial != null) return serial;

        raw = firstNonEmpty(
                systemProperty("ro.vendor.gsm.serial"),
                shellGetprop("ro.vendor.gsm.serial"));
        serial = extractMt90Serial(raw);
        if (serial != null) return serial;

        return extractMt90Serial(readGenericSerial());
    }

    private static String readUrovoSerial() {
        String value = cleanSerial(invokeUrovoMethod("getDeviceId"));
        if (value != null) return value;

        value = cleanSerial(invokeUrovoMethod("getTIDSN"));
        if (value != null) return value;

        value = cleanSerial(rawBuildGetSerial());
        if (value != null) return value;

        try {
            value = cleanSerial(Build.SERIAL);
            if (value != null) return value;
        } catch (Throwable ignored) { }

        String[] keys = new String[] {
                "persist.sys.product.serialno",
                "persist.sys.device.serial",
                "ro.serialno",
                "ro.boot.serialno"
        };
        for (String key : keys) {
            value = cleanSerial(systemProperty(key));
            if (value != null) return value;

            value = cleanSerial(shellGetprop(key));
            if (value != null) return value;
        }

        return null;
    }

    private static String readGenericSerial() {
        String value = cleanSerial(rawBuildGetSerial());
        if (value != null) return value;

        try {
            value = cleanSerial(Build.SERIAL);
            if (value != null) return value;
        } catch (Throwable ignored) { }

        String[] keys = new String[] {
                "ro.serialno",
                "ro.boot.serialno",
                "persist.sys.serialno",
                "persist.vendor.serialno",
                "sys.serialnumber",
                "ro.vendor.serialno",
                "ro.vendor.product.serial",
                "vendor.serialno"
        };

        for (String key : keys) {
            value = cleanSerial(systemProperty(key));
            if (value != null) return value;

            value = cleanSerial(shellGetprop(key));
            if (value != null) return value;
        }

        return null;
    }

    private static String rawBuildGetSerial() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;
        try {
            return Build.getSerial();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String invokeUrovoMethod(String methodName) {
        try {
            Class<?> cls = Class.forName("android.device.DeviceManager");
            Object manager = cls.getDeclaredConstructor().newInstance();
            Method method = cls.getMethod(methodName);
            Object value = method.invoke(manager);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String systemProperty(String key) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            Method get = cls.getMethod("get", String.class);
            Object value = get.invoke(null, key);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String shellGetprop(String key) {
        BufferedReader reader = null;
        try {
            Process process = Runtime.getRuntime().exec(
                    new String[] { "/system/bin/getprop", key });
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return line == null ? null : line.trim();
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) { }
            }
        }
    }

    private static String extractMt90Serial(String raw) {
        if (raw == null) return null;
        Matcher matcher = MT90_SERIAL.matcher(raw.trim());
        if (!matcher.find()) return null;
        return matcher.group(1).toUpperCase(Locale.US);
    }

    private static String cleanSerial(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.length() < 6 || value.length() > 40) return null;

        String lower = value.toLowerCase(Locale.US);
        if ("unknown".equals(lower)
                || "null".equals(lower)
                || "none".equals(lower)) {
            return null;
        }
        if ("0123456789".equals(value)) return null;
        if (value.matches("(?i)[0f]+")) return null;
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{5,39}")) return null;
        return value;
    }

    private static String firstNonEmpty(String first, String second) {
        if (first != null && first.trim().length() > 0) return first;
        return second;
    }

    private static String safe(String value) {
        if (value == null || value.trim().length() == 0) return "Unknown";
        return value.trim();
    }
}
