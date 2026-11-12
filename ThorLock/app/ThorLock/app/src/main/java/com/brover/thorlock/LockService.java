package com.brover.thorlock;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

/**
 * Запасной вариант, если спецвозможность не включена: обычная фоновая служба.
 * Если её убивают (смахнул из недавних), она просит систему перезапустить её.
 */
public class LockService extends Service {

    private static final String CHANNEL = "thorlock_bg";
    private static final int NOTIF_ID = 1;

    private Prefs prefs;
    private LockEngine engine; // null — работает движок спецвозможностей

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
        prefs = new Prefs(this);
        createChannel();
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
        LockEngine cur = LockEngine.current;
        if (cur == null || !cur.accessibility) {
            engine = new LockEngine(this, false, () -> {
                if (!prefs.isEnabled()) stopSelf();
            });
            engine.start();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (engine == null) {
            // Работают спецвозможности — передаём команду им, а сами не нужны.
            LockEngine cur = LockEngine.current;
            if (cur != null) cur.handle(action);
            stopSelf();
            return START_NOT_STICKY;
        }
        engine.handle(action);
        if (!prefs.isEnabled() && !engine.isBusy()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    /** Приложение смахнули из недавних — на всякий случай просим перезапуск через пару секунд. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        EventLog.add(this, "Приложение смахнули из недавних");
        if (engine != null && prefs.isEnabled()) scheduleRestart();
        super.onTaskRemoved(rootIntent);
    }

    private void scheduleRestart() {
        try {
            PendingIntent pi = PendingIntent.getForegroundService(this, 10,
                    new Intent(this, LockService.class).setAction(LockEngine.ACTION_REFRESH),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = getSystemService(AlarmManager.class);
            long at = SystemClock.elapsedRealtime() + 2000;
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (Exception e) {
            EventLog.add(this, "Перезапуск не запланирован: " + e);
        }
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Фоновая защита",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent lock = PendingIntent.getForegroundService(this, 2,
                new Intent(this, LockService.class).setAction(LockEngine.ACTION_LOCK_NOW),
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
        if (engine != null) {
            EventLog.add(this, "Фоновая служба остановлена");
            engine.stop();
            if (prefs.isEnabled() && LockEngine.current == null) scheduleRestart();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
