package com.brover.thorlock;

import android.accessibilityservice.AccessibilityService;
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
    private String foregroundClass; // окно (activity) на экране сейчас
    private String gameClass;       // окно игры (например, экран эмуляции Azahar)
    private boolean returning;      // идёт возврат — не переучиваем gameClass
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

    void onWindow(String pkg, String cls) {
        String prev = foreground;
        foreground = pkg;
        foregroundClass = cls;
        if (isGameCandidate(pkg)) {
            if (!pkg.equals(game)) {
                if (prev != null && !prev.equals(pkg)) gameOpener = prev;
                game = pkg;
                gameClass = null;
            }
            // Запоминаем экран игры (activity), чтобы потом отличить его от главного меню эмулятора.
            if (!returning && cls != null && cls.contains("Activity")) gameClass = cls;
            minimized = null;
            return;
        }
        if (pkg.equals(prev)) return;
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

    /**
     * Возврат игры.
     * Шаг 1 — как советует фронтенд: через переключатель задач (двойное «Недавние»).
     * Так задача возвращается как есть, без запуска чего-либо нового.
     * Шаг 2 (если не вышло) — запуск эмулятора; если поверх игры открылось главное меню
     * эмулятора, один раз «Назад», чтобы показать игру под ним.
     */
    private void bringToFront(String pkg) {
        if (foreground != null && !isLauncherLike(foreground)) {
            EventLog.add(host, "Game return cancelled: " + foreground + " is on screen now");
            return;
        }
        returning = true;
        if (host instanceof AccessibilityService) {
            AccessibilityService a11y = (AccessibilityService) host;
            EventLog.add(host, "Game return step 1: task switcher (game window: " + gameClass + ")");
            a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
            handler.postDelayed(() -> a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS), 450);
            handler.postDelayed(() -> afterTaskSwitcher(pkg), 1800);
        } else {
            launchApp(pkg);
        }
    }

    private void afterTaskSwitcher(String pkg) {
        if (pkg.equals(foreground)) {
            EventLog.add(host, "Game brought back via task switcher: " + foregroundClass);
            returning = false;
            return;
        }
        EventLog.add(host, "Task switcher did not work (on screen: " + foreground + "), step 2");
        launchApp(pkg);
    }

    private void launchApp(String pkg) {
        try {
            Intent i = host.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                EventLog.add(host, "Game return failed: no launch intent for " + pkg);
                returning = false;
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
            host.startActivity(i, opts.toBundle());
            EventLog.add(host, "Game return step 2: launched " + pkg);
            handler.postDelayed(() -> closeMenuOverGame(pkg), 1300);
        } catch (Throwable t) {
            EventLog.add(host, "Game return failed: " + t);
            returning = false;
        }
    }

    /** Если вместо экрана игры видно другое окно эмулятора (главное меню) — «Назад» один раз. */
    private void closeMenuOverGame(String pkg) {
        boolean menuOnTop = pkg.equals(foreground) && gameClass != null && foregroundClass != null
                && !foregroundClass.equals(gameClass);
        if (menuOnTop && host instanceof AccessibilityService) {
            EventLog.add(host, "Emulator menu (" + foregroundClass + ") over the game, pressing Back");
            ((AccessibilityService) host).performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            handler.postDelayed(() -> {
                EventLog.add(host, "After Back on screen: " + foreground + " / " + foregroundClass
                        + (gameClass != null && gameClass.equals(foregroundClass) ? " (game is back)" : ""));
                returning = false;
            }, 1200);
        } else {
            EventLog.add(host, "Game return done, on screen: " + foreground + " / " + foregroundClass);
            returning = false;
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
