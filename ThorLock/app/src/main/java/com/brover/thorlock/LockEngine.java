package com.brover.thorlock;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.KeyEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Вся логика защиты: следит за экраном, считает задержку, показывает блокировку.
 * Живёт либо внутри службы спецвозможностей (надёжно, система её не убивает),
 * либо внутри обычной фоновой службы (запасной вариант).
 * Журнал событий пишется на английском — он технический, для диагностики.
 */
public class LockEngine {

    public static final String ACTION_REFRESH = "com.brover.thorlock.REFRESH";
    public static final String ACTION_LOCK_NOW = "com.brover.thorlock.LOCK_NOW";
    public static final String ACTION_TEST_LOCK = "com.brover.thorlock.TEST_LOCK";
    public static final String ACTION_BOOT = "com.brover.thorlock.BOOT";
    public static final String ACTION_TEST_VIDEO = "com.brover.thorlock.TEST_VIDEO";

    /** Сколько после открытия крышки/разблокировки записывать смену приложений на экране. */
    private static final long WATCH_AFTER_MS = 20_000;

    /** Текущий работающий движок (из спецвозможностей или из службы). */
    static volatile LockEngine current;

    final boolean accessibility;
    private final Context host;
    private final Prefs prefs;
    private final LockOverlay overlay;
    private final DisplayManager displayManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Integer, Integer> displayStates = new HashMap<>();
    private boolean started;
    private boolean screenOff;
    private long screenOffAt = -1;
    private long screenOnAt = 0;
    private long watchUntil = 0;
    private String foregroundApp;
    private final GameGuard gameGuard;

    private final Runnable delayedLock = this::checkDelayedLock;
    private final Runnable rebuildOverlay = this::doRebuildOverlay;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) onScreenOff("SCREEN_OFF");
            else if (Intent.ACTION_SCREEN_ON.equals(a)) onScreenOn("SCREEN_ON");
        }
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int id) {
            Display d = displayManager.getDisplay(id);
            EventLog.add(host, "Display ADDED id=" + id + (d != null ? " \"" + d.getName() + "\" state="
                    + stateName(d.getState()) : ""));
            if (d != null) displayStates.put(id, d.getState());
            scheduleRebuild();
        }

        @Override
        public void onDisplayRemoved(int id) {
            EventLog.add(host, "Display REMOVED id=" + id);
            displayStates.remove(id);
            scheduleRebuild();
        }

        @Override
        public void onDisplayChanged(int id) {
            Display d = displayManager.getDisplay(id);
            if (d == null) return;
            int st = d.getState();
            Integer prev = displayStates.put(id, st);
            if (id != Display.DEFAULT_DISPLAY) {
                if (prev == null || prev != st) {
                    EventLog.add(host, "Display " + id + " (bottom) state: " + stateName(st));
                }
                return;
            }
            if (st == Display.STATE_ON) {
                onScreenOn("display on");
            } else if (st == Display.STATE_OFF || st == Display.STATE_DOZE
                    || st == Display.STATE_DOZE_SUSPEND) {
                onScreenOff("display " + stateName(st));
            }
        }
    };

    /** Отправить команду работающему движку, а если его нет — запустить фоновую службу. */
    public static void send(Context c, String action) {
        LockEngine e = current;
        if (e != null) {
            e.handle(action);
        } else {
            try {
                LockService.start(c, action);
            } catch (Exception ex) {
                EventLog.add(c, "Could not start background service: " + ex);
            }
        }
    }

    public static boolean isRunning() {
        return current != null;
    }

    public LockEngine(Context host, boolean accessibility, Runnable onUnlockedExtra) {
        this.host = host;
        this.accessibility = accessibility;
        this.prefs = new Prefs(host);
        this.displayManager = host.getSystemService(DisplayManager.class);
        this.gameGuard = new GameGuard(host, prefs);
        this.overlay = new LockOverlay(host, accessibility, () -> {
            watchUntil = SystemClock.elapsedRealtime() + WATCH_AFTER_MS;
            if (onUnlockedExtra != null) onUnlockedExtra.run();
        });
    }

    public void start() {
        if (started) return;
        started = true;
        current = this;

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) host.registerReceiver(screenReceiver, f, Context.RECEIVER_EXPORTED);
        else host.registerReceiver(screenReceiver, f);
        displayManager.registerDisplayListener(displayListener, handler);
        for (Display d : displayManager.getDisplays()) displayStates.put(d.getDisplayId(), d.getState());

        PowerManager pm = host.getSystemService(PowerManager.class);
        screenOff = pm != null && !pm.isInteractive();
        screenOnAt = SystemClock.elapsedRealtime();

        EventLog.add(host, "Protection started via " + (accessibility ? "Accessibility" : "background service")
                + " (v" + MainActivity.VERSION + ", enabled=" + prefs.isEnabled()
                + ", delay " + prefs.getDelaySec() + " s)");

        if (prefs.isLocked() && hasSecret()) overlay.show(false);
    }

    public void stop() {
        if (!started) return;
        started = false;
        if (current == this) current = null;
        handler.removeCallbacks(delayedLock);
        handler.removeCallbacks(rebuildOverlay);
        cancelAlarm();
        try {
            host.unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        try {
            displayManager.unregisterDisplayListener(displayListener);
        } catch (Exception ignored) {
        }
        overlay.hide();
    }

    public void handle(String action) {
        if (ACTION_LOCK_NOW.equals(action)) {
            lockNow("button");
        } else if (ACTION_TEST_LOCK.equals(action)) {
            if (hasSecret() && !overlay.isShowing()) overlay.show(true);
        } else if (ACTION_TEST_VIDEO.equals(action)) {
            overlay.testVideo();
        } else if (ACTION_BOOT.equals(action)) {
            if (prefs.isEnabled() && prefs.isLockOnBoot()) lockNow("after boot");
        }
    }

    public boolean isShowing() {
        return overlay.isShowing();
    }

    /** Блокировка или видео на экране — службу останавливать нельзя. */
    public boolean isBusy() {
        return overlay.isShowing() || overlay.isPlayingVideo();
    }

    /** Экран включён уже какое-то время (не момент открытия крышки). */
    public boolean isScreenSettled() {
        return !screenOff && SystemClock.elapsedRealtime() - screenOnAt > 2000;
    }

    /** Кнопки геймпада, перехваченные спецвозможностями. */
    public boolean onKeyEvent(KeyEvent e) {
        gameGuard.onKey(e);
        return overlay.handleKey(e);
    }

    /** Спецвозможности сообщают, какое приложение сейчас на экране. */
    public void onForegroundApp(String pkg) {
        if (pkg == null || pkg.equals(foregroundApp)) return;
        String prev = foregroundApp;
        foregroundApp = pkg;
        gameGuard.onForeground(pkg);
        if (screenOff || SystemClock.elapsedRealtime() < watchUntil || overlay.isShowing()) {
            EventLog.add(host, "Foreground app: " + prev + " -> " + pkg);
        }
    }

    private boolean hasSecret() {
        return prefs.hasSecret(prefs.getMode());
    }

    private boolean active() {
        return prefs.isEnabled() && hasSecret();
    }

    /** Нижний экран пропал/появился — пересобрать блокировку, чтобы не остаться без рисунка. */
    private void scheduleRebuild() {
        handler.removeCallbacks(rebuildOverlay);
        handler.postDelayed(rebuildOverlay, 500);
    }

    private void doRebuildOverlay() {
        if (!overlay.isShowing()) return;
        EventLog.add(host, "Displays changed while locked: rebuilding lock windows");
        overlay.rebuild();
    }

    private void onScreenOff(String source) {
        if (screenOff) return;
        screenOff = true;
        gameGuard.setScreenOff(true);
        MainActivity.resetVerification();
        String app = " [fg: " + foregroundApp + "]";
        if (overlay.isShowing()) {
            EventLog.add(host, "Screen OFF (" + source + "), lock already shown" + app);
            return;
        }
        if (!active()) {
            EventLog.add(host, "Screen OFF (" + source + "), protection disabled" + app);
            return;
        }
        screenOffAt = SystemClock.elapsedRealtime();
        int delay = prefs.getDelaySec();
        EventLog.add(host, "Screen OFF (" + source + "), lock in " + delay + " s" + app);
        if (delay <= 0) {
            lockNow("immediately on screen off");
            return;
        }
        long ms = delay * 1000L;
        handler.postDelayed(delayedLock, ms);
        scheduleAlarm(screenOffAt + ms);
    }

    private void onScreenOn(String source) {
        if (!screenOff) return;
        screenOff = false;
        screenOnAt = SystemClock.elapsedRealtime();
        gameGuard.setScreenOff(false);
        watchUntil = screenOnAt + WATCH_AFTER_MS;
        handler.removeCallbacks(delayedLock);
        cancelAlarm();
        String app = " [fg: " + foregroundApp + "]";
        if (screenOffAt > 0 && active() && !overlay.isShowing()) {
            long off = SystemClock.elapsedRealtime() - screenOffAt;
            EventLog.add(host, "Screen ON (" + source + "), was off " + (off / 1000) + " s" + app);
            if (off >= prefs.getDelaySec() * 1000L) lockNow("screen was off long enough");
        } else {
            EventLog.add(host, "Screen ON (" + source + ")" + app);
        }
        screenOffAt = -1;
    }

    void checkDelayedLock() {
        if (!screenOff || screenOffAt <= 0 || overlay.isShowing() || !active()) return;
        long off = SystemClock.elapsedRealtime() - screenOffAt;
        if (off >= prefs.getDelaySec() * 1000L - 1500) lockNow("timer while closed");
    }

    private void lockNow(String reason) {
        if (!hasSecret()) {
            EventLog.add(host, "Not locking: no password set");
            return;
        }
        MainActivity.resetVerification();
        if (overlay.isShowing() && overlay.isTest()) overlay.hide();
        EventLog.add(host, "LOCK: " + reason + " [fg: " + foregroundApp + "]");
        prefs.setLocked(true);
        if (!overlay.show(false)) prefs.setLocked(false);
    }

    static String stateName(int st) {
        switch (st) {
            case Display.STATE_ON: return "ON";
            case Display.STATE_OFF: return "OFF";
            case Display.STATE_DOZE: return "DOZE";
            case Display.STATE_DOZE_SUSPEND: return "DOZE_SUSPEND";
            default: return String.valueOf(st);
        }
    }

    private PendingIntent alarmIntent() {
        Intent i = new Intent(host, AlarmReceiver.class);
        return PendingIntent.getBroadcast(host, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void scheduleAlarm(long atElapsed) {
        AlarmManager am = host.getSystemService(AlarmManager.class);
        if (am == null) return;
        try {
            boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, alarmIntent());
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, alarmIntent());
            }
        } catch (Exception e) {
            EventLog.add(host, "Alarm not scheduled: " + e);
        }
    }

    private void cancelAlarm() {
        AlarmManager am = host.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(alarmIntent());
    }
}
