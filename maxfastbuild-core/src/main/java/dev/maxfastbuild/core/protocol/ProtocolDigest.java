package dev.maxfastbuild.core.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Small, non-secret digest helper used to detect truncated or corrupted transfers. */
public final class ProtocolDigest {
    public static final int SHA256_BASE64_LENGTH = 43;

    private ProtocolDigest() {}

    public static String sha256(byte[] value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean matches(String expected, byte[] value) {
        return expected != null && expected.length() == SHA256_BASE64_LENGTH
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                sha256(value).getBytes(StandardCharsets.US_ASCII));
    }
}
