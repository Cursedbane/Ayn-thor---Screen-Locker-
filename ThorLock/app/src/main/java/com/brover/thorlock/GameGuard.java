package com.brover.thorlock;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.KeyEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * «Возвращать игру после открытия крышки».
 *
 * На Thor при закрытии/открытии крышки нижний экран отключается и включается обратно,
 * и иногда лаунчер или фронтенд вылезает на передний план — эмулятор уходит в фон.
 * Если потом запустить игру из фронтенда, она начнётся заново.
 *
 * GameGuard видит (через спецвозможности), что игру сменил лаунчер/фронтенд
 * в момент закрытия или открытия крышки без нажатия кнопки «Домой»,
 * и возвращает игру на верхний экран — так же, как если нажать на неё в недавних.
 */
final class GameGuard {

    private static final long CLOSE_WINDOW_MS = 5_000;   // свернуло за 5 с до выключения экрана
    private static final long OPEN_WINDOW_MS = 15_000;   // или в течение 15 с после включения
    private static final long NAV_KEY_GRACE_MS = 2_000;  // недавно нажали «Домой»/«Недавние» — не мешаем
    private static final long RETURN_DELAY_MS = 600;

    private final Context host;
    private final Prefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String foreground;      // приложение на экране сейчас
    private String game;            // последнее «обычное» приложение (игра/эмулятор)
    private String gameOpener;      // что было перед игрой (обычно фронтенд)
    private String minimized;       // игра, которую только что свернуло
    private long minimizedAt;
    private long lastNavKeyAt = -1;
    private boolean screenOff;
    private long lastScreenOffAt = -1;
    private long screenOnAt;
    private boolean returnedThisCycle;
    private Set<String> homeApps;

    GameGuard(Context host, Prefs prefs) {
        this.host = host;
        this.prefs = prefs;
    }

    void setScreenOff(boolean off) {
        long now = SystemClock.elapsedRealtime();
        if (off) {
            screenOff = true;
            lastScreenOffAt = now;
            returnedThisCycle = false;
        } else {
            screenOff = false;
            screenOnAt = now;
            maybeReturn("screen on");
        }
    }

    /** Все кнопки проходят через спецвозможности: запоминаем, если пользователь сам ушёл с игры. */
    void onKey(KeyEvent e) {
        int kc = e.getKeyCode();
        if (kc == KeyEvent.KEYCODE_HOME || kc == KeyEvent.KEYCODE_APP_SWITCH) {
            lastNavKeyAt = SystemClock.elapsedRealtime();
        }
    }

    void onForeground(String pkg) {
        String prev = foreground;
        foreground = pkg;
        if (isGameCandidate(pkg)) {
            if (!pkg.equals(game)) gameOpener = prev;
            game = pkg;
            minimized = null;
            return;
        }
        if (prev != null && prev.equals(game) && isLauncherLike(pkg)) {
            minimized = prev;
            minimizedAt = SystemClock.elapsedRealtime();
            EventLog.add(host, "Game went to background: " + prev + " -> " + pkg);
            maybeReturn("game minimized");
        }
    }

    private void maybeReturn(String why) {
        if (minimized == null || returnedThisCycle) return;
        if (!prefs.isAutoReturn()) return;
        if (foreground == null || !isLauncherLike(foreground)) return;
        long now = SystemClock.elapsedRealtime();
        boolean nearClose = lastScreenOffAt > 0 && minimizedAt >= lastScreenOffAt - CLOSE_WINDOW_MS;
        boolean nearOpen = !screenOff && now - screenOnAt <= OPEN_WINDOW_MS && minimizedAt >= screenOnAt - CLOSE_WINDOW_MS;
        if (!nearClose && !nearOpen) return;
        if (lastNavKeyAt > 0 && minimizedAt - lastNavKeyAt <= NAV_KEY_GRACE_MS && minimizedAt >= lastNavKeyAt) {
            EventLog.add(host, "Game return skipped: Home/Recents was pressed");
            minimized = null;
            return;
        }
        if (screenOff) return; // вернём, когда экран включится (setScreenOff(false))
        returnedThisCycle = true;
        final String target = minimized;
        minimized = null;
        EventLog.add(host, "Will bring the game back (" + why + "): " + target);
        handler.postDelayed(() -> bringToFront(target), RETURN_DELAY_MS);
    }

    private void bringToFront(String pkg) {
        if (foreground != null && !isLauncherLike(foreground)) {
            EventLog.add(host, "Game return cancelled: " + foreground + " is on screen now");
            return;
        }
        try {
            Intent i = host.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                EventLog.add(host, "Game return failed: no launch intent for " + pkg);
                return;
            }
            // Как нажатие на значок: существующая задача выходит вперёд, игра не перезапускается.
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
            host.startActivity(i, opts.toBundle());
            EventLog.add(host, "Game brought back: " + pkg);
        } catch (Throwable t) {
            EventLog.add(host, "Game return failed: " + t);
        }
    }

    /** «Игра» — любое обычное приложение: не лаунчер, не системный интерфейс, не мы. */
    private boolean isGameCandidate(String pkg) {
        return !isHome(pkg) && !pkg.equals(host.getPackageName()) && !pkg.startsWith("com.android.systemui");
    }

    /** Лаунчер или то, откуда запустили игру (фронтенд). */
    private boolean isLauncherLike(String pkg) {
        return isHome(pkg) || pkg.equals(gameOpener);
    }

    private boolean isHome(String pkg) {
        if (homeApps == null) {
            homeApps = new HashSet<>();
            try {
                Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                List<ResolveInfo> list = host.getPackageManager().queryIntentActivities(home, 0);
                for (ResolveInfo r : list) homeApps.add(r.activityInfo.packageName);
            } catch (Throwable ignored) {
            }
            EventLog.add(host, "Home apps: " + homeApps);
        }
        return homeApps.contains(pkg);
    }
}
