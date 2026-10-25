package net.xuanzhi.launcher.util;

import androidx.annotation.NonNull;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class HashUtil {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HashUtil() {}

    @NonNull
    public static String sha1Hex(@NonNull File f) throws IOException {
        try (InputStream is = new FileInputStream(f)) {
            MessageDigest d = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) d.update(buf, 0, n);
            return hex(d.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e); // SHA-1 不可能缺
        }
    }

    @NonNull
    public static String hex(@NonNull byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xff;
            c[i * 2] = HEX[v >>> 4];
            c[i * 2 + 1] = HEX[v & 0x0f];
        }
        return new String(c);
    }
}
