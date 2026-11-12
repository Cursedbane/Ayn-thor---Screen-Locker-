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

/**
 * Вся логика защиты: следит за экраном, считает задержку, показывает блокировку.
 * Живёт либо внутри службы спецвозможностей (надёжно, система её не убивает),
 * либо внутри обычной фоновой службы (запасной вариант).
 */
public class LockEngine {

    public static final String ACTION_REFRESH = "com.brover.thorlock.REFRESH";
    public static final String ACTION_LOCK_NOW = "com.brover.thorlock.LOCK_NOW";
    public static final String ACTION_TEST_LOCK = "com.brover.thorlock.TEST_LOCK";
    public static final String ACTION_BOOT = "com.brover.thorlock.BOOT";
    public static final String ACTION_TEST_VIDEO = "com.brover.thorlock.TEST_VIDEO";

    /** Текущий работающий движок (из спецвозможностей или из службы). */
    static volatile LockEngine current;

    final boolean accessibility;
    private final Context host;
    private final Prefs prefs;
    private final LockOverlay overlay;
    private final DisplayManager displayManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean started;
    private boolean screenOff;
    private long screenOffAt = -1;

    private final Runnable delayedLock = this::checkDelayedLock;

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
        public void onDisplayAdded(int id) {}

        @Override
        public void onDisplayRemoved(int id) {}

        @Override
        public void onDisplayChanged(int id) {
            if (id != Display.DEFAULT_DISPLAY) return;
            Display d = displayManager.getDisplay(id);
            if (d == null) return;
            int st = d.getState();
            if (st == Display.STATE_ON) {
                onScreenOn("дисплей включён");
            } else if (st == Display.STATE_OFF || st == Display.STATE_DOZE
                    || st == Display.STATE_DOZE_SUSPEND) {
                onScreenOff("дисплей выключен");
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
                EventLog.add(c, "Не удалось запустить службу: " + ex);
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
        this.overlay = new LockOverlay(host, accessibility, onUnlockedExtra);
        this.displayManager = host.getSystemService(DisplayManager.class);
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

        PowerManager pm = host.getSystemService(PowerManager.class);
        screenOff = pm != null && !pm.isInteractive();

        EventLog.add(host, "Защита запущена через " + (accessibility ? "спецвозможности" : "фоновую службу")
                + " (вкл=" + prefs.isEnabled() + ", задержка " + prefs.getDelaySec() + " с)");

        if (prefs.isLocked() && hasSecret()) overlay.show(false);
    }

    public void stop() {
        if (!started) return;
        started = false;
        if (current == this) current = null;
        handler.removeCallbacks(delayedLock);
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
            lockNow("по кнопке");
        } else if (ACTION_TEST_LOCK.equals(action)) {
            if (hasSecret() && !overlay.isShowing()) overlay.show(true);
        } else if (ACTION_TEST_VIDEO.equals(action)) {
            overlay.testVideo();
        } else if (ACTION_BOOT.equals(action)) {
            if (prefs.isEnabled() && prefs.isLockOnBoot()) lockNow("после включения");
        }
    }

    public boolean isShowing() {
        return overlay.isShowing();
    }

    /** Блокировка или видео на экране — службу останавливать нельзя. */
    public boolean isBusy() {
        return overlay.isShowing() || overlay.isPlayingVideo();
    }

    /** Кнопки геймпада, перехваченные спецвозможностями. */
    public boolean onKeyEvent(KeyEvent e) {
        return overlay.handleKey(e);
    }

    private boolean hasSecret() {
        return prefs.hasSecret(prefs.getMode());
    }

    private boolean active() {
        return prefs.isEnabled() && hasSecret();
    }

    private void onScreenOff(String source) {
        if (screenOff) return;
        screenOff = true;
        MainActivity.resetVerification();
        if (overlay.isShowing()) {
            EventLog.add(host, "Экран выкл [" + source + "], блокировка уже на экране");
            return;
        }
        if (!active()) {
            EventLog.add(host, "Экран выкл [" + source + "], но защита выключена");
            return;
        }
        screenOffAt = SystemClock.elapsedRealtime();
        int delay = prefs.getDelaySec();
        EventLog.add(host, "Экран выкл [" + source + "], блокировка через " + delay + " с");
        if (delay <= 0) {
            lockNow("сразу при выключении");
            return;
        }
        long ms = delay * 1000L;
        handler.postDelayed(delayedLock, ms);
        scheduleAlarm(screenOffAt + ms);
    }

    private void onScreenOn(String source) {
        if (!screenOff) return;
        screenOff = false;
        handler.removeCallbacks(delayedLock);
        cancelAlarm();
        if (screenOffAt > 0 && active() && !overlay.isShowing()) {
            long off = SystemClock.elapsedRealtime() - screenOffAt;
            EventLog.add(host, "Экран вкл [" + source + "], был выключен " + (off / 1000) + " с");
            if (off >= prefs.getDelaySec() * 1000L) lockNow("экран был выключен долго");
        } else {
            EventLog.add(host, "Экран вкл [" + source + "]");
        }
        screenOffAt = -1;
    }

    void checkDelayedLock() {
        if (!screenOff || screenOffAt <= 0 || overlay.isShowing() || !active()) return;
        long off = SystemClock.elapsedRealtime() - screenOffAt;
        if (off >= prefs.getDelaySec() * 1000L - 1500) lockNow("таймер, пока закрыт");
    }

    private void lockNow(String reason) {
        if (!hasSecret()) {
            EventLog.add(host, "Не блокирую: пароль не задан");
            return;
        }
        MainActivity.resetVerification();
        if (overlay.isShowing() && overlay.isTest()) overlay.hide();
        EventLog.add(host, "Блокировка: " + reason);
        prefs.setLocked(true);
        if (!overlay.show(false)) prefs.setLocked(false);
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
            EventLog.add(host, "Будильник не поставлен: " + e);
        }
    }

    private void cancelAlarm() {
        AlarmManager am = host.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(alarmIntent());
    }
}
