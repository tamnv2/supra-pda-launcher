package vn.supra.pdalauncher;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class TimeCodeVerifier {
    private TimeCodeVerifier() { }

    static boolean verify(String candidate) {
        if (candidate == null || !candidate.matches("\\d{8,}")) return false;

        long now = System.currentTimeMillis();
        SimpleDateFormat formatter = new SimpleDateFormat("HHmm", Locale.US);
        formatter.setTimeZone(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));

        for (int offsetMinutes = -5; offsetMinutes <= 5; offsetMinutes++) {
            String token = formatter.format(
                    new Date(now + offsetMinutes * 60L * 1000L));
            if (candidate.contains(token)) return true;
        }
        return false;
    }
}
