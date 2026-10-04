package vn.supra.pdalauncher;

import android.content.Context;
import android.os.Build;
import android.telephony.TelephonyManager;

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

    private static final Pattern IMEI_PATTERN = Pattern.compile("(?<!\\d)(\\d{15})(?!\\d)");

    private DeviceIdentifier() { }

    public static Result resolve(Context context) {
        String manufacturer = safe(Build.MANUFACTURER);
        String model = safe(Build.MODEL);
        String fingerprint = (manufacturer + " " + model).toLowerCase(Locale.US);

        if (fingerprint.contains("urovo") || fingerprint.contains("dt50")) {
            String imei = readImei1(context);
            return imei != null
                    ? new Result("IMEI1", imei, manufacturer + " " + model)
                    : new Result("IMEI1", null, "Không đọc được IMEI1 • " + manufacturer + " " + model);
        }

        if (fingerprint.contains("newland") || fingerprint.contains("mt90")) {
            String serial = readSerial();
            return serial != null
                    ? new Result("S/N", serial, manufacturer + " " + model)
                    : new Result("S/N", null, "Không đọc được S/N • " + manufacturer + " " + model);
        }

        String serial = readSerial();
        if (serial != null) {
            return new Result("S/N", serial, manufacturer + " " + model);
        }

        String imei = readImei1(context);
        if (imei != null) {
            return new Result("IMEI1", imei, manufacturer + " " + model);
        }

        return new Result("S/N / IMEI1", null,
                "Không đọc được mã thiết bị • " + manufacturer + " " + model);
    }

    public static boolean isTargetPda() {
        String fingerprint = (safe(Build.MANUFACTURER) + " " + safe(Build.MODEL))
                .toLowerCase(Locale.US);
        return fingerprint.contains("urovo")
                || fingerprint.contains("dt50")
                || fingerprint.contains("newland")
                || fingerprint.contains("mt90");
    }

    private static String readSerial() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                String value = cleanSerial(Build.getSerial());
                if (value != null) return value;
            } catch (Throwable ignored) { }
        }

        try {
            String value = cleanSerial(Build.SERIAL);
            if (value != null) return value;
        } catch (Throwable ignored) { }

        String[] keys = new String[] {
                "ro.serialno",
                "ro.boot.serialno",
                "persist.sys.serialno",
                "persist.vendor.serialno",
                "sys.serialnumber"
        };
        for (String key : keys) {
            String value = cleanSerial(systemProperty(key));
            if (value != null) return value;
        }
        return null;
    }

    private static String readImei1(Context context) {
        try {
            TelephonyManager tm =
                    (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        String value = cleanImei(tm.getImei(0));
                        if (value != null) return value;
                    } catch (Throwable ignored) { }
                    try {
                        String value = cleanImei(tm.getImei());
                        if (value != null) return value;
                    } catch (Throwable ignored) { }
                } else {
                    try {
                        String value = cleanImei(tm.getDeviceId());
                        if (value != null) return value;
                    } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable ignored) { }

        String[] keys = new String[] {
                "persist.radio.imei1",
                "persist.radio.imei",
                "persist.vendor.radio.imei",
                "ril.gsm.imei",
                "gsm.imei1",
                "ro.ril.oem.imei"
        };
        for (String key : keys) {
            String value = cleanImei(systemProperty(key));
            if (value != null) return value;
        }
        return null;
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

    private static String cleanSerial(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.length() < 6 || value.length() > 40) return null;

        String lower = value.toLowerCase(Locale.US);
        if ("unknown".equals(lower) || "null".equals(lower) || "none".equals(lower)) return null;
        if ("0123456789".equals(value)) return null;
        if (value.matches("(?i)[0f]+")) return null;
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{5,39}")) return null;
        return value;
    }

    private static String cleanImei(String raw) {
        if (raw == null) return null;
        Matcher matcher = IMEI_PATTERN.matcher(raw);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (isValidLuhn(candidate)) return candidate;
        }

        String compact = raw.replaceAll("[^0-9]", "");
        if (compact.length() == 15 && isValidLuhn(compact)) return compact;
        return null;
    }

    private static boolean isValidLuhn(String value) {
        if (value == null || value.length() != 15) return false;
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = value.length() - 1; i >= 0; i--) {
            int n = value.charAt(i) - '0';
            if (n < 0 || n > 9) return false;
            if (doubleDigit) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }

    private static String safe(String value) {
        if (value == null || value.trim().length() == 0) return "Unknown";
        return value.trim();
    }
}
