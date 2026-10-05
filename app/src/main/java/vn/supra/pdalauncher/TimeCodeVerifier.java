package vn.supra.pdalauncher;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class TimeCodeVerifier {
    private TimeCodeVerifier() { }

    static boolean verify(String candidate) {
        if (candidate == null || !candidate.matches("\\d{4}")) return false;

        long now = System.currentTimeMillis();
        SimpleDateFormat formatter = new SimpleDateFormat("HHmm", Locale.US);

        for (int offsetMinutes = -5; offsetMinutes <= 5; offsetMinutes++) {
            String token = formatter.format(
                    new Date(now + offsetMinutes * 60L * 1000L));
            if (token.equals(candidate)) return true;
        }
        return false;
    }
}
