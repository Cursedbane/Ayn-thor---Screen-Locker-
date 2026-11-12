package com.brover.thorlock;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/** Показывает окна блокировки поверх всего сразу на всех экранах (верхнем и нижнем). */
public class LockOverlay implements Unlocker.Callback {

    private static final int TEST_SECONDS = 60;

    private final Context app;
    private final Runnable onUnlocked;
    private final List<WindowManager> managers = new ArrayList<>();
    private final List<View> views = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Unlocker unlocker;
    private boolean test;
    private int testLeft;

    private final Runnable testTick = new Runnable() {
        @Override
        public void run() {
            if (!isShowing() || !test) return;
            testLeft--;
            if (testLeft <= 0) {
                EventLog.add(app, "Тест: блокировка снята по таймеру");
                onUnlocked();
                return;
            }
            unlocker.footer("Тест: само снимется через " + testLeft + " с");
            handler.postDelayed(this, 1000);
        }
    };

    public LockOverlay(Context c, Runnable onUnlocked) {
        this.app = c.getApplicationContext();
        this.onUnlocked = onUnlocked;
    }

    public boolean isShowing() { return !views.isEmpty(); }

    public boolean isTest() { return test; }

    public boolean show(boolean testMode) {
        if (isShowing()) return true;
        if (!Settings.canDrawOverlays(app)) {
            EventLog.add(app, "ОШИБКА: нет разрешения «Поверх других окон»");
            return false;
        }
        Prefs prefs = new Prefs(app);
        String mode = prefs.getMode();
        boolean patternMode = Prefs.MODE_PATTERN.equals(mode);
        test = testMode;
        unlocker = new Unlocker(app, mode, this);
        EventLog.add(app, "Показ блокировки" + (test ? " (тест)" : "") + ", способ: "
                + (patternMode ? "рисунок" : "геймпад"));

        DisplayManager dm = app.getSystemService(DisplayManager.class);
        List<Display> displays = new ArrayList<>();
        for (Display d : dm.getDisplays()) {
            EventLog.add(app, "  экран id=" + d.getDisplayId() + " «" + d.getName() + "» flags="
                    + d.getFlags() + " state=" + d.getState());
            if (d.isValid() && (d.getFlags() & Display.FLAG_PRIVATE) == 0) displays.add(d);
        }
        Display primary = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (displays.isEmpty() && primary != null) displays.add(primary);

        Display secondary = null;
        for (Display d : displays) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) { secondary = d; break; }
        }
        Display patternDisplay = primary;
        if (!Prefs.DISPLAY_PRIMARY.equals(prefs.getPatternDisplay()) && secondary != null) {
            patternDisplay = secondary;
        }

        // 1) Сначала экран с рисунком. Если не получилось — рисунок переезжает на основной экран,
        //    чтобы никогда не остаться без способа разблокировки.
        int patternId = -1;
        if (patternMode) {
            if (patternDisplay != null && addPanel(patternDisplay, true)) {
                patternId = patternDisplay.getDisplayId();
            } else if (primary != null && primary != patternDisplay && addPanel(primary, true)) {
                patternId = primary.getDisplayId();
                EventLog.add(app, "Рисунок перенесён на основной экран");
            }
            if (patternId < 0) {
                EventLog.add(app, "ОШИБКА: не удалось показать рисунок ни на одном экране");
                hide();
                return false;
            }
        }
        // 2) Остальные экраны просто закрываем.
        for (Display d : displays) {
            if (d.getDisplayId() == patternId) continue;
            addPanel(d, false);
        }

        if (!isShowing()) return false;
        View focus = views.get(0);
        focus.requestFocus();

        if (test) {
            testLeft = TEST_SECONDS;
            unlocker.footer("Тест: само снимется через " + testLeft + " с");
            handler.postDelayed(testTick, 1000);
        }
        return true;
    }

    private boolean addPanel(Display d, boolean showPattern) {
        try {
            Context dc = app.createDisplayContext(d);
            Context wc = Build.VERSION.SDK_INT >= 30
                    ? dc.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                    : dc;
            WindowManager wm = (WindowManager) wc.getSystemService(Context.WINDOW_SERVICE);
            LockPanel panel = new LockPanel(wc, unlocker, showPattern, "Устройство заблокировано", true);
            hideSystemBars(panel);
            wm.addView(panel, params());
            managers.add(wm);
            views.add(panel);
            EventLog.add(app, "  окно на экране " + d.getDisplayId() + (showPattern ? " (рисунок)" : "") + " — ок");
            return true;
        } catch (Throwable e) {
            EventLog.add(app, "  окно на экране " + d.getDisplayId() + " — ОШИБКА: " + e);
            return false;
        }
    }

    public void hide() {
        handler.removeCallbacks(testTick);
        for (int i = 0; i < views.size(); i++) {
            try {
                managers.get(i).removeViewImmediate(views.get(i));
            } catch (Throwable e) {
                EventLog.add(app, "Ошибка при снятии окна: " + e);
            }
        }
        views.clear();
        managers.clear();
        test = false;
    }

    @Override
    public void onUnlocked() {
        hide();
        new Prefs(app).setLocked(false);
        EventLog.add(app, "Разблокировано");
        if (onUnlocked != null) onUnlocked.run();
    }

    private static WindowManager.LayoutParams params() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE);
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        lp.setTitle("ThorLock");
        return lp;
    }

    @SuppressWarnings("deprecation")
    private static void hideSystemBars(View v) {
        v.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
