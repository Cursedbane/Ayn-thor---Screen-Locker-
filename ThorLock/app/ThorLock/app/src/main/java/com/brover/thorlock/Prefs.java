package com.brover.thorlock;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Все настройки приложения. Секреты хранятся только как соль + SHA-256. */
public final class Prefs {
    public static final String MODE_PATTERN = "pattern";
    public static final String MODE_GAMEPAD = "gamepad";

    public static final String DISPLAY_AUTO = "auto";
    public static final String DISPLAY_PRIMARY = "primary";
    public static final String DISPLAY_SECONDARY = "secondary";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("thorlock", Context.MODE_PRIVATE);
    }

    public boolean isEnabled() { return sp.getBoolean("enabled", false); }
    public void setEnabled(boolean v) { sp.edit().putBoolean("enabled", v).apply(); }

    public String getMode() { return sp.getString("mode", MODE_PATTERN); }
    public void setMode(String m) { sp.edit().putString("mode", m).apply(); }

    public int getDelaySec() { return sp.getInt("delay", 60); }
    public void setDelaySec(int s) { sp.edit().putInt("delay", s).apply(); }

    public boolean isLockOnBoot() { return sp.getBoolean("lock_on_boot", true); }
    public void setLockOnBoot(boolean v) { sp.edit().putBoolean("lock_on_boot", v).apply(); }

    public String getPatternDisplay() { return sp.getString("pattern_display", DISPLAY_AUTO); }
    public void setPatternDisplay(String v) { sp.edit().putString("pattern_display", v).apply(); }

    public boolean isVideoEnabled() { return sp.getBoolean("video_on", false); }
    public void setVideoEnabled(boolean v) { sp.edit().putBoolean("video_on", v).apply(); }

    public String getVideoUri() { return sp.getString("video_uri", null); }
    public void setVideoUri(String u) { sp.edit().putString("video_uri", u).apply(); }

    public boolean isVideoMuted() { return sp.getBoolean("video_muted", false); }
    public void setVideoMuted(boolean v) { sp.edit().putBoolean("video_muted", v).apply(); }

    /** Флаг "сейчас заблокировано" — переживает убийство процесса. */
    public boolean isLocked() { return sp.getBoolean("locked", false); }
    public void setLocked(boolean v) { sp.edit().putBoolean("locked", v).commit(); }

    public boolean hasSecret(String mode) { return sp.contains("hash_" + mode); }

    public int getSecretLength(String mode) { return sp.getInt("len_" + mode, 0); }

    public void setSecret(String mode, String value, int length) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String saltHex = hex(salt);
        sp.edit()
                .putString("salt_" + mode, saltHex)
                .putString("hash_" + mode, hash(saltHex, value))
                .putInt("len_" + mode, length)
                .commit();
    }

    public boolean check(String mode, String value) {
        String salt = sp.getString("salt_" + mode, null);
        String stored = sp.getString("hash_" + mode, null);
        if (salt == null || stored == null) return false;
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                hash(salt, value).getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(String salt, String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            md.update(value.getBytes(StandardCharsets.UTF_8));
            return hex(md.digest());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
