package vn.supra.pdalauncher;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.text.Normalizer;
import java.util.Locale;

public final class DeviceIdentifier {
    public static final class Result {
        public final String label;
        public final String value;
        public final String detail;
        public final String source;
        public final String raw;
        public final String normalized;
        public final String serialRaw;
        public final String serialNormalized;
        public final String imei1;
        public final String androidId;

        Result(
                String label,
                String value,
                String detail,
                String source,
                String raw,
                String normalized,
                String serialRaw,
                String serialNormalized,
                String imei1,
                String androidId) {
            this.label = label;
            this.value = value;
            this.detail = detail;
            this.source = source;
            this.raw = raw;
            this.normalized = normalized;
            this.serialRaw = serialRaw;
            this.serialNormalized = serialNormalized;
            this.imei1 = imei1;
            this.androidId = androidId;
        }

        public boolean isAvailable() {
            return value != null && value.length() > 0;
        }
    }

    private static final class Candidate {
        final String raw;
        final String normalized;

        Candidate(String raw, String normalized) {
            this.raw = raw;
            this.normalized = normalized;
        }
    }

    private DeviceIdentifier() { }

    public static Result resolve(Context context) {
        String manufacturer = safe(Build.MANUFACTURER);
        String model = safe(Build.MODEL);
        String fingerprint = (manufacturer + " " + model).toLowerCase(Locale.US);
        String androidId = readAndroidId(context);

        Candidate serial = null;
        Candidate imei = null;

        if (fingerprint.contains("newland") || fingerprint.contains("mt90")) {
            serial = readNewlandSerial();
            if (serial == null) serial = readGenericSerial();
        } else if (fingerprint.contains("urovo") || fingerprint.contains("dt50")) {
            imei = readUrovoImei();
            serial = readUrovoSerial();
            if (serial == null) serial = readGenericSerial();
        } else {
            serial = readGenericSerial();
        }

        Candidate primary;
        String source;
        String label;

        if (imei != null) {
            primary = imei;
            source = "IMEI1";
            label = "IMEI1";
        } else if (serial != null) {
            primary = serial;
            source = "SERIAL";
            label = "S/N";
        } else {
            Candidate android = cleanCandidate(androidId);
            if (android != null) {
                primary = android;
                source = "ANDROID_ID";
                label = "ID";
            } else {
                primary = null;
                source = "UNAVAILABLE";
                label = "S/N";
            }
        }

        String detail = manufacturer + " " + model;
        if (primary == null) {
            return new Result(
                    label, null, "Không đọc được mã thiết bị • " + detail,
                    source, "", "", serial == null ? "" : serial.raw,
                    serial == null ? "" : serial.normalized,
                    imei == null ? "" : imei.normalized,
                    androidId == null ? "" : androidId);
        }

        return new Result(
                label,
                primary.raw,
                detail,
                source,
                primary.raw,
                primary.normalized,
                serial == null ? "" : serial.raw,
                serial == null ? "" : serial.normalized,
                imei == null ? "" : imei.normalized,
                androidId == null ? "" : androidId);
    }

    private static Candidate readNewlandSerial() {
        String[] keys = new String[] {
                "vendor.gsm.serial",
                "ro.vendor.gsm.serial",
                "ro.serialno",
                "ro.boot.serialno",
                "persist.sys.product.serialno",
                "persist.sys.device.serial"
        };
        for (String key : keys) {
            Candidate value = cleanCandidate(systemProperty(key));
            if (value != null) return value;
            value = cleanCandidate(shellGetprop(key));
            if (value != null) return value;
        }
        return null;
    }

    private static Candidate readUrovoImei() {
        String[] methods = new String[] {
                "getImei1",
                "getIMEI1",
                "getImei",
                "getIMEI",
                "getDeviceId"
        };
        for (String method : methods) {
            Candidate value = cleanCandidate(invokeUrovoMethod(method));
            if (value != null && looksLikeImei(value.normalized)) return value;
        }
        return null;
    }

    private static Candidate readUrovoSerial() {
        String[] methods = new String[] {
                "getTIDSN",
                "getSerialNumber",
                "getSN",
                "getDeviceId"
        };
        for (String method : methods) {
            Candidate value = cleanCandidate(invokeUrovoMethod(method));
            if (value != null && !looksLikeImei(value.normalized)) return value;
        }

        String[] keys = new String[] {
                "persist.sys.product.serialno",
                "persist.sys.device.serial",
                "ro.serialno",
                "ro.boot.serialno",
                "ro.vendor.serialno"
        };
        for (String key : keys) {
            Candidate value = cleanCandidate(systemProperty(key));
            if (value != null) return value;
            value = cleanCandidate(shellGetprop(key));
            if (value != null) return value;
        }
        return null;
    }

    private static Candidate readGenericSerial() {
        Candidate value = cleanCandidate(rawBuildGetSerial());
        if (value != null) return value;

        try {
            value = cleanCandidate(Build.SERIAL);
            if (value != null) return value;
        } catch (Throwable ignored) { }

        String[] keys = new String[] {
                "ro.serialno",
                "ro.boot.serialno",
                "persist.sys.serialno",
                "persist.vendor.serialno",
                "persist.sys.product.serialno",
                "persist.sys.device.serial",
                "sys.serialnumber",
                "ro.vendor.serialno",
                "ro.vendor.product.serial",
                "vendor.serialno",
                "vendor.gsm.serial",
                "ro.vendor.gsm.serial"
        };

        for (String key : keys) {
            value = cleanCandidate(systemProperty(key));
            if (value != null) return value;
            value = cleanCandidate(shellGetprop(key));
            if (value != null) return value;
        }
        return null;
    }

    private static Candidate cleanCandidate(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.length() < 4 || value.length() > 96) return null;

        String lower = value.toLowerCase(Locale.US);
        if ("unknown".equals(lower)
                || "null".equals(lower)
                || "none".equals(lower)
                || "n/a".equals(lower)
                || "na".equals(lower)
                || "not available".equals(lower)) {
            return null;
        }

        String normalized = normalizeIdentifier(value);
        if (normalized.length() < 4 || normalized.length() > 96) return null;
        if ("0123456789".equals(normalized)) return null;
        if (normalized.matches("0+")
                || normalized.matches("F+")
                || normalized.matches("X+")) {
            return null;
        }

        int alphaNumeric = 0;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isLetterOrDigit(value.charAt(i))) alphaNumeric++;
        }
        if (alphaNumeric < 4) return null;

        // Keep the vendor value for display/barcode. Only reject control characters.
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch)) return null;
        }
        return new Candidate(value, normalized);
    }

    public static String normalizeIdentifier(String raw) {
        if (raw == null) return "";
        String value = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
                .toUpperCase(Locale.US);
        return value.replaceAll("[^A-Z0-9]", "");
    }

    private static boolean looksLikeImei(String normalized) {
        return normalized != null
                && normalized.matches("[0-9]{14,17}")
                && !normalized.matches("0+");
    }

    private static String rawBuildGetSerial() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;
        try {
            return Build.getSerial();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readAndroidId(Context context) {
        try {
            String value = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            Candidate candidate = cleanCandidate(value);
            return candidate == null ? "" : candidate.raw;
        } catch (Throwable ignored) {
            return "";
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

    private static String safe(String value) {
        if (value == null || value.trim().length() == 0) return "Unknown";
        return value.trim();
    }
}
