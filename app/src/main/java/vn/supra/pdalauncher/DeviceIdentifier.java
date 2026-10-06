package vn.supra.pdalauncher;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern NEWLAND_MT90_SERIAL =
            Pattern.compile("(?i)(MT90[A-Z0-9._-]{6,39})");
    private static final Pattern GENERIC_SERIAL_TOKEN =
            Pattern.compile("([A-Za-z0-9][A-Za-z0-9._-]{5,39})");
    private static final Pattern MEID_DECIMAL =
            Pattern.compile("(?<![0-9])(\\d{18})(?![0-9])");
    private static final Pattern MEID_HEX =
            Pattern.compile("(?i)(?<![0-9A-F])([0-9A-F]{14})(?![0-9A-F])");

    private DeviceIdentifier() { }

    public static Result resolve(Context context) {
        String manufacturer = safe(Build.MANUFACTURER);
        String model = safe(Build.MODEL);
        String fingerprint = (manufacturer + " " + model).toLowerCase(Locale.US);
        String androidId = readAndroidId(context);

        Candidate serial = null;
        Candidate imei = null;
        Candidate meid = null;
        boolean isUrovo = fingerprint.contains("urovo") || fingerprint.contains("dt50");

        if (fingerprint.contains("newland") || fingerprint.contains("mt90")) {
            serial = readNewlandSerial();
            if (serial == null) serial = readGenericSerial();
        } else if (isUrovo) {
            // Operational identity rule for DT50:
            // MEID is the primary S/N shown/barcoded by Launcher.
            // IMEI1 is inventory metadata only and must never become the primary identifier.
            meid = readUrovoMeid(context);
            imei = readUrovoImei();
            if (meid != null) {
                serial = meid;
            } else {
                serial = readUrovoSerial();
                if (serial == null) serial = readGenericSerial();
            }
        } else {
            serial = readGenericSerial();
        }

        Candidate primary;
        String source;
        String label;

        if (isUrovo && meid != null) {
            primary = meid;
            source = "MEID";
            label = "S/N";
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
            Candidate value = cleanNewlandCandidate(systemProperty(key));
            if (value != null) return value;
            value = cleanNewlandCandidate(shellGetprop(key));
            if (value != null) return value;
        }
        return null;
    }

    private static Candidate cleanNewlandCandidate(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.length() == 0) return null;

        Matcher mt90 = NEWLAND_MT90_SERIAL.matcher(value);
        if (mt90.find()) {
            return cleanCandidate(mt90.group(1).toUpperCase(Locale.US));
        }

        Matcher tokenMatcher = GENERIC_SERIAL_TOKEN.matcher(value);
        Candidate best = null;
        while (tokenMatcher.find()) {
            Candidate candidate = cleanCandidate(tokenMatcher.group(1));
            if (candidate == null) continue;
            if (best == null || candidate.normalized.length() > best.normalized.length()) {
                best = candidate;
            }
        }
        if (best != null) return best;

        return cleanCandidate(value);
    }

    private static Candidate readUrovoMeid(Context context) {
        // Some Urovo firmware exposes MEID through vendor-only methods even when
        // standard Android persistent-ID APIs are restricted.
        String[] methods = new String[] {
                "getMeid",
                "getMEID",
                "getMeid1",
                "getMEID1",
                "getDeviceMeid",
                "getDeviceMEID"
        };
        for (String method : methods) {
            Candidate value = cleanMeidCandidate(invokeUrovoMethod(method));
            if (value != null) return value;
        }

        Candidate telephony = cleanMeidCandidate(readTelephonyMeid(context));
        if (telephony != null) return telephony;

        String[] keys = new String[] {
                "ril.meid",
                "gsm.meid",
                "persist.radio.meid",
                "persist.vendor.radio.meid",
                "vendor.ril.meid",
                "ro.ril.meid",
                "ro.vendor.radio.meid",
                "ro.boot.meid"
        };
        for (String key : keys) {
            Candidate value = cleanMeidCandidate(systemProperty(key));
            if (value != null) return value;
            value = cleanMeidCandidate(shellGetprop(key));
            if (value != null) return value;
        }

        return scanGetpropForMeid();
    }

    private static Candidate readUrovoImei() {
        String[] methods = new String[] {
                "getImei1",
                "getIMEI1",
                "getImei",
                "getIMEI"
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

    private static Candidate cleanMeidCandidate(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.length() == 0) return null;

        Matcher decimal = MEID_DECIMAL.matcher(value);
        if (decimal.find()) {
            return cleanCandidate(decimal.group(1));
        }

        Matcher hex = MEID_HEX.matcher(value);
        if (hex.find()) {
            return cleanCandidate(hex.group(1).toUpperCase(Locale.US));
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

    private static String readTelephonyMeid(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;
        try {
            TelephonyManager manager =
                    (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (manager == null) return null;

            try {
                String value = manager.getMeid(0);
                if (value != null && value.trim().length() > 0) return value;
            } catch (Throwable ignored) { }

            try {
                return manager.getMeid();
            } catch (Throwable ignored) {
                return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Candidate scanGetpropForMeid() {
        BufferedReader reader = null;
        try {
            Process process = Runtime.getRuntime().exec(new String[] { "/system/bin/getprop" });
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase(Locale.US);
                if (!lower.contains("meid")) continue;

                int open = line.lastIndexOf('[');
                int close = line.lastIndexOf(']');
                String rawValue = (open >= 0 && close > open)
                        ? line.substring(open + 1, close)
                        : line;
                Candidate value = cleanMeidCandidate(rawValue);
                if (value != null) {
                    try { process.destroy(); } catch (Throwable ignored) { }
                    return value;
                }
            }
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) { }
            }
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
