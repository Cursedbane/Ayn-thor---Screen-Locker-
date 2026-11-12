package com.brover.thorlock;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Короткий журнал событий, виден в настройках — чтобы понять, что происходит на устройстве. */
public final class EventLog {
    private static final int MAX_LINES = 80;

    private EventLog() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("thorlock_log", Context.MODE_PRIVATE);
    }

    public static synchronized void add(Context c, String msg) {
        Log.i("ThorLock", msg);
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String all = time + "  " + msg + "\n" + sp(c).getString("log", "");
        String[] lines = all.split("\n");
        if (lines.length > MAX_LINES) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < MAX_LINES; i++) sb.append(lines[i]).append('\n');
            all = sb.toString();
        }
        sp(c).edit().putString("log", all).apply();
    }

    public static String get(Context c) {
        String s = sp(c).getString("log", "");
        return s.isEmpty() ? "(пусто)" : s;
    }

    public static void clear(Context c) {
        sp(c).edit().remove("log").apply();
    }
}
