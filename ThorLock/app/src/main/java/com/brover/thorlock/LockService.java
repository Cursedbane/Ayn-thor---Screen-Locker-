package com.brover.thorlock;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;

/**
 * Фоновая служба: ловит выключение/включение экрана (закрытие/открытие Thor)
 * и показывает блокировку, если экран был выключен дольше заданного времени.
 * Слушает и системные события SCREEN_OFF/ON, и состояние основного дисплея —
 * на некоторых устройствах крышка гасит дисплей без "сна" системы.
 */
public class LockService extends Service {

    public static final String ACTION_REFRESH = "com.brover.thorlock.REFRESH";
    public static final String ACTION_LOCK_NOW = "com.brover.thorlock.LOCK_NOW";
    public static final String ACTION_TEST_LOCK = "com.brover.thorlock.TEST_LOCK";
    public static final String ACTION_BOOT = "com.brover.thorlock.BOOT";

    private static final String CHANNEL = "thorlock_bg";
    private static final int NOTIF_ID = 1;

    static volatile LockService instance;

    private Prefs prefs;
    private LockOverlay overlay;
    private DisplayManager displayManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean screenOff = false;
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
                onScreenOff("дисплей выключен (state=" + st + ")");
            }
        }
    };

    public static void start(Context c, String action) {
        Intent i = new Intent(c, LockService.class).setAction(action);
        c.startForegroundService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, LockService.class));
    }

    public static boolean isRunning() {
        return instance != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = new Prefs(this);
        overlay = new LockOverlay(this, () -> {
            if (!prefs.isEnabled()) stopSelf();
        });

        createChannel();
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(screenReceiver, f);

        displayManager = getSystemService(DisplayManager.class);
        displayManager.registerDisplayListener(displayListener, handler);

        PowerManager pm = getSystemService(PowerManager.class);
        screenOff = pm != null && !pm.isInteractive();

        EventLog.add(this, "Служба запущена (защита " + (prefs.isEnabled() ? "вкл" : "выкл")
                + ", задержка " + prefs.getDelaySec() + " с)");

        // Если процесс убили, пока устройство было заблокировано, — блокируем снова.
        if (prefs.isLocked() && hasSecret()) overlay.show(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_LOCK_NOW.equals(action)) {
            lockNow("по кнопке");
        } else if (ACTION_TEST_LOCK.equals(action)) {
            if (hasSecret() && !overlay.isShowing()) overlay.show(true);
        } else if (ACTION_BOOT.equals(action)) {
            if (prefs.isEnabled() && prefs.isLockOnBoot()) lockNow("после включения");
        }
        if (!prefs.isEnabled() && !overlay.isShowing()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
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
            EventLog.add(this, "Экран выкл [" + source + "], блокировка уже на экране");
            return;
        }
        if (!active()) {
            EventLog.add(this, "Экран выкл [" + source + "], но защита неактивна");
            return;
        }
        screenOffAt = SystemClock.elapsedRealtime();
        int delay = prefs.getDelaySec();
        EventLog.add(this, "Экран выкл [" + source + "], блокировка через " + delay + " с");
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
            EventLog.add(this, "Экран вкл [" + source + "], был выключен " + (off / 1000) + " с");
            if (off >= prefs.getDelaySec() * 1000L) lockNow("экран был выключен долго");
        } else {
            EventLog.add(this, "Экран вкл [" + source + "]");
        }
        screenOffAt = -1;
    }

    /** Вызывается таймером, пока экран ещё выключен: блокируем заранее, чтобы при открытии ничего не мелькнуло. */
    void checkDelayedLock() {
        if (!screenOff || screenOffAt <= 0 || overlay.isShowing() || !active()) return;
        long off = SystemClock.elapsedRealtime() - screenOffAt;
        if (off >= prefs.getDelaySec() * 1000L - 1500) lockNow("таймер, пока закрыт");
    }

    private void lockNow(String reason) {
        if (!hasSecret()) {
            EventLog.add(this, "Не блокирую: пароль не задан");
            return;
        }
        MainActivity.resetVerification();
        if (overlay.isShowing() && overlay.isTest()) overlay.hide();
        EventLog.add(this, "Блокировка: " + reason);
        prefs.setLocked(true);
        if (!overlay.show(false)) prefs.setLocked(false);
    }

    private PendingIntent alarmIntent() {
        Intent i = new Intent(this, AlarmReceiver.class);
        return PendingIntent.getBroadcast(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void scheduleAlarm(long atElapsed) {
        AlarmManager am = getSystemService(AlarmManager.class);
        if (am == null) return;
        try {
            boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, alarmIntent());
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, alarmIntent());
            }
        } catch (Exception e) {
            EventLog.add(this, "Будильник не поставлен: " + e);
        }
    }

    private void cancelAlarm() {
        AlarmManager am = getSystemService(AlarmManager.class);
        if (am != null) am.cancel(alarmIntent());
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Фоновая защита",
                NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent lock = PendingIntent.getForegroundService(this, 2,
                new Intent(this, LockService.class).setAction(ACTION_LOCK_NOW),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_lock)
                .setContentTitle("Thor Lock активен")
                .setContentText("Блокировка после закрытия крышки")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Заблокировать", lock).build())
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        EventLog.add(this, "Служба остановлена");
        instance = null;
        handler.removeCallbacks(delayedLock);
        cancelAlarm();
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        try {
            displayManager.unregisterDisplayListener(displayListener);
        } catch (Exception ignored) {
        }
        overlay.hide();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
