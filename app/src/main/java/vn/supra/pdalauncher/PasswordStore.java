package vn.supra.pdalauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class PasswordStore {
    private static final String PREF = "launcher_security";
    private static final String KEY_SALT = "salt";
    private static final String KEY_HASH = "hash";
    private static final String DEFAULT_PASSWORD = "11111111";
    private static final int ITERATIONS = 120000;

    private PasswordStore() {}

    static void ensureInitialized(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (!p.contains(KEY_HASH) || !p.contains(KEY_SALT)) setPassword(c, DEFAULT_PASSWORD);
    }

    static boolean verify(Context c, String candidate) {
        if (candidate == null) return false;
        try {
            SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String saltText = p.getString(KEY_SALT, null);
            String hashText = p.getString(KEY_HASH, null);
            if (saltText == null || hashText == null) return false;
            byte[] salt = Base64.decode(saltText, Base64.NO_WRAP);
            byte[] expected = Base64.decode(hashText, Base64.NO_WRAP);
            byte[] actual = derive(candidate.toCharArray(), salt);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean verifyRecovery(String candidate) {
        if (candidate == null || candidate.length() <= 8) return false;
        String token = new SimpleDateFormat("HHmm", Locale.US).format(new Date());
        return candidate.contains(token);
    }

    static boolean setPassword(Context c, String password) {
        if (password == null || password.length() < 8) return false;
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] hash = derive(password.toCharArray(), salt);
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                    .apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }
}
