package vn.supra.pdalauncher;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.TelephonyManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
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

    public static String diagnosticReport(Context context) {
        StringBuilder out = new StringBuilder();
        out.append("Launcher PDA ").append(BuildConfig.VERSION_NAME).append('\n');
        out.append("Hãng: ").append(safe(Build.MANUFACTURER)).append('\n');
        out.append("Model: ").append(safe(Build.MODEL)).append('\n');
        out.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("READ_PHONE_STATE: ")
                .append(hasPhonePermission(context) ? "GRANTED" : "DENIED").append("\n\n");

        out.append("=== API Android ===\n");
        out.append("Build.getSerial(): ").append(probeBuildGetSerial()).append('\n');
        out.append("Build.SERIAL: ").append(displayRaw(Build.SERIAL)).append('\n');
        out.append("Telephony.getImei(0): ").append(probeImeiSlot0(context)).append('\n');
        out.append("Telephony.getImei(): ").append(probeImei(context)).append('\n');
        out.append("Telephony.getDeviceId(): ").append(probeDeviceId(context)).append("\n\n");

        out.append("=== API hãng ===\n");
        out.append("Urovo DeviceManager.getDeviceId(): ")
                .append(probeUrovoDeviceId()).append('\n');
        out.append("Urovo DeviceManager.getTIDSN(): ")
                .append(probeUrovoTidSn()).append("\n\n");

        out.append("=== SystemProperties ===\n");
        String[] keys = diagnosticKeys();
        for (String key : keys) {
            out.append(key).append(": ")
                    .append(probeSystemProperty(key)).append('\n');
        }

        out.append("\n=== /system/bin/getprop từng khóa ===\n");
        for (String key : keys) {
            out.append(key).append(": ")
                    .append(probeShellGetprop(key)).append('\n');
        }

        out.append("\n=== getprop dòng liên quan ===\n");
        List<String> relevant = shellRelevantGetpropLines();
        if (relevant.isEmpty()) {
            out.append("(không có dòng đọc được)\n");
        } else {
            for (String line : relevant) {
                out.append(line).append('\n');
            }
        }

        out.append("\nKết quả ưu tiên hiện tại: ");
        Result result = resolve(context);
        if (result.isAvailable()) {
            out.append(result.label).append(" = ").append(result.value);
        } else {
            out.append("KHÔNG ĐỌC ĐƯỢC");
        }
        out.append("\n\nBáo cáo này chỉ hiển thị trên PDA, ứng dụng không tự gửi ra ngoài.");
        return out.toString();
    }

    private static String readSerial() {
        String value = cleanSerial(rawBuildGetSerial());
        if (value != null) return value;

        try {
            value = cleanSerial(Build.SERIAL);
            if (value != null) return value;
        } catch (Throwable ignored) { }

        String[] keys = serialKeys();
        for (String key : keys) {
            value = cleanSerial(systemProperty(key));
            if (value != null) return value;
            value = cleanSerial(shellGetprop(key));
            if (value != null) return value;
        }
        return null;
    }

    private static String readImei1(Context context) {
        String value = cleanImei(rawImeiSlot0(context));
        if (value != null) return value;

        value = cleanImei(rawImei(context));
        if (value != null) return value;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            value = cleanImei(rawDeviceId(context));
            if (value != null) return value;
        }

        String[] keys = imeiKeys();
        for (String key : keys) {
            value = cleanImei(systemProperty(key));
            if (value != null) return value;
            value = cleanImei(shellGetprop(key));
            if (value != null) return value;
        }
        return null;
    }

    private static boolean hasPhonePermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static String rawBuildGetSerial() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;
        try {
            return Build.getSerial();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String probeBuildGetSerial() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "N/A (< Android 8)";
        try {
            return displayRaw(Build.getSerial());
        } catch (Throwable e) {
            return failure(e);
        }
    }

    private static String rawImeiSlot0(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;
        try {
            TelephonyManager tm =
                    (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            return tm == null ? null : tm.getImei(0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String probeImeiSlot0(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "N/A (< Android 8)";
        try {
            TelephonyManager tm =
                    (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            return tm == null ? "TelephonyManager=null" : displayRaw(tm.getImei(0));
        } catch (Throwable e) {
            return failure(e);
        }
    }

    private static String rawImei(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;
        try {
            TelephonyManager tm =
                    (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            return tm == null ? null : tm.getImei();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String probeImei(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "N/A (< Android 8)";
        try {
            TelephonyManager tm =
                    (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            return tm == null ? "TelephonyManager=null" : displayRaw(tm.getImei());
        } catch (Throwable e) {
            return failure(e);
        }
    }

    private static String rawDeviceId(Context context) {
        try {
            TelephonyManager tm =
                    (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            return tm == null ? null : tm.getDeviceId();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String probeDeviceId(Context context) {
        try {
            TelephonyManager tm =
                    (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            return tm == null ? "TelephonyManager=null" : displayRaw(tm.getDeviceId());
        } catch (Throwable e) {
            return failure(e);
        }
    }

    private static String probeUrovoDeviceId() {
        return probeUrovoMethod("getDeviceId");
    }

    private static String probeUrovoTidSn() {
        return probeUrovoMethod("getTIDSN");
    }

    private static String probeUrovoMethod(String methodName) {
        try {
            Class<?> cls = Class.forName("android.device.DeviceManager");
            Object manager = cls.getDeclaredConstructor().newInstance();
            Method method = cls.getMethod(methodName);
            Object value = method.invoke(manager);
            return displayRaw(value == null ? null : String.valueOf(value));
        } catch (ClassNotFoundException e) {
            return "CLASS_NOT_FOUND";
        } catch (NoSuchMethodException e) {
            return "METHOD_NOT_FOUND";
        } catch (Throwable e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return failure(cause);
        }
    }

    private static String[] serialKeys() {
        return new String[] {
                "ro.serialno",
                "ro.boot.serialno",
                "persist.sys.serialno",
                "persist.vendor.serialno",
                "sys.serialnumber",
                "ro.vendor.serialno",
                "ro.vendor.product.serial",
                "vendor.serialno"
        };
    }

    private static String[] imeiKeys() {
        return new String[] {
                "persist.radio.imei1",
                "persist.radio.imei",
                "persist.vendor.radio.imei",
                "persist.vendor.radio.imei1",
                "ril.gsm.imei",
                "gsm.imei1",
                "gsm.imei",
                "ro.ril.oem.imei",
                "vendor.ril.imei",
                "vendor.ril.imei1"
        };
    }

    private static String[] diagnosticKeys() {
        String[] serial = serialKeys();
        String[] imei = imeiKeys();
        String[] all = new String[serial.length + imei.length];
        System.arraycopy(serial, 0, all, 0, serial.length);
        System.arraycopy(imei, 0, all, serial.length, imei.length);
        return all;
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

    private static String probeSystemProperty(String key) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            Method get = cls.getMethod("get", String.class);
            Object value = get.invoke(null, key);
            return displayRaw(value == null ? null : String.valueOf(value));
        } catch (Throwable e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return failure(cause);
        }
    }

    private static String shellGetprop(String key) {
        BufferedReader reader = null;
        try {
            Process process = Runtime.getRuntime().exec(
                    new String[] { "/system/bin/getprop", key });
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            try { process.waitFor(); } catch (InterruptedException ignored) {
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

    private static String probeShellGetprop(String key) {
        try {
            return displayRaw(shellGetprop(key));
        } catch (Throwable e) {
            return failure(e);
        }
    }

    private static List<String> shellRelevantGetpropLines() {
        ArrayList<String> result = new ArrayList<String>();
        BufferedReader reader = null;
        try {
            Process process = Runtime.getRuntime().exec(new String[] { "/system/bin/getprop" });
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null && result.size() < 40) {
                String lower = line.toLowerCase(Locale.US);
                if (lower.contains("serial")
                        || lower.contains("imei")
                        || lower.contains("meid")
                        || lower.contains("deviceid")
                        || lower.contains("device_id")) {
                    result.add(line.trim());
                }
            }
            try { process.waitFor(); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        } catch (Throwable e) {
            result.add("ERROR: " + failure(e));
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) { }
            }
        }
        return result;
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

    private static String displayRaw(String raw) {
        if (raw == null) return "EMPTY";
        String value = raw.trim();
        if (value.length() == 0) return "EMPTY";
        return value;
    }

    private static String failure(Throwable e) {
        if (e == null) return "ERROR";
        String name = e.getClass().getSimpleName();
        String message = e.getMessage();
        if (message == null || message.trim().length() == 0) return name;
        message = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (message.length() > 120) message = message.substring(0, 120) + "...";
        return name + ": " + message;
    }

    private static String safe(String value) {
        if (value == null || value.trim().length() == 0) return "Unknown";
        return value.trim();
    }
}
