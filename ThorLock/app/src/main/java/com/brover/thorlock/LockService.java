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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Фоновая служба: ловит выключение/включение экрана (закрытие/открытие Thor)
 * и показывает блокировку, если экран был выключен дольше заданного времени.
 */
public class LockService extends Service {

    public static final String ACTION_REFRESH = "com.brover.thorlock.REFRESH";
    public static final String ACTION_LOCK_NOW = "com.brover.thorlock.LOCK_NOW";
    public static final String ACTION_BOOT = "com.brover.thorlock.BOOT";
    public static final String ACTION_ALARM = "com.brover.thorlock.ALARM";

    private static final String CHANNEL = "thorlock_bg";
    private static final int NOTIF_ID = 1;

    static LockService instance;

    private Prefs prefs;
    private LockOverlay overlay;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long screenOffAt = -1;

    private final Runnable delayedLock = this::checkDelayedLock;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) onScreenOff();
            else if (Intent.ACTION_SCREEN_ON.equals(a)) onScreenOn();
        }
    };

    public static void start(Context c, String action) {
        Intent i = new Intent(c, LockService.class).setAction(action);
        c.startForegroundService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, LockService.class));
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
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver, f);

        // Если процесс убили, пока устройство было заблокировано, — блокируем снова.
        if (prefs.isLocked() && hasSecret()) overlay.show();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_LOCK_NOW.equals(action)) {
            lockNow();
        } else if (ACTION_BOOT.equals(action)) {
            if (prefs.isEnabled() && prefs.isLockOnBoot()) lockNow();
        } else if (ACTION_ALARM.equals(action)) {
            checkDelayedLock();
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

    private void onScreenOff() {
        MainActivity.resetVerification();
        if (overlay.isShowing() || !active()) return;
        screenOffAt = SystemClock.elapsedRealtime();
        int delay = prefs.getDelaySec();
        if (delay <= 0) {
            lockNow();
            return;
        }
        long ms = delay * 1000L;
        handler.postDelayed(delayedLock, ms);
        scheduleAlarm(screenOffAt + ms);
    }

    private void onScreenOn() {
        handler.removeCallbacks(delayedLock);
        cancelAlarm();
        if (screenOffAt > 0 && active() && !overlay.isShowing()) {
            long off = SystemClock.elapsedRealtime() - screenOffAt;
            if (off >= prefs.getDelaySec() * 1000L) lockNow();
        }
        screenOffAt = -1;
    }

    /** Вызывается таймером, пока экран ещё выключен: блокируем заранее, чтобы при открытии ничего не мелькнуло. */
    void checkDelayedLock() {
        if (screenOffAt <= 0 || overlay.isShowing() || !active()) return;
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isInteractive()) return;
        long off = SystemClock.elapsedRealtime() - screenOffAt;
        if (off >= prefs.getDelaySec() * 1000L - 1500) lockNow();
    }

    private void lockNow() {
        if (!hasSecret()) return;
        MainActivity.resetVerification();
        prefs.setLocked(true);
        overlay.show();
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
        } catch (Exception ignored) {
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
        instance = null;
        handler.removeCallbacks(delayedLock);
        cancelAlarm();
        try {
            unregisterReceiver(screenReceiver);
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
