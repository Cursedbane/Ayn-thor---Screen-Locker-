package com.brover.thorlock;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/** Показывает окна блокировки поверх всего сразу на всех экранах (верхнем и нижнем). */
public class LockOverlay implements Unlocker.Callback {

    private static final String TAG = "ThorLock";

    private final Context app;
    private final Runnable onUnlocked;
    private final List<WindowManager> managers = new ArrayList<>();
    private final List<View> views = new ArrayList<>();

    public LockOverlay(Context c, Runnable onUnlocked) {
        this.app = c.getApplicationContext();
        this.onUnlocked = onUnlocked;
    }

    public boolean isShowing() { return !views.isEmpty(); }

    public boolean show() {
        if (isShowing()) return true;
        if (!Settings.canDrawOverlays(app)) {
            Log.w(TAG, "No overlay permission");
            return false;
        }
        Prefs prefs = new Prefs(app);
        String mode = prefs.getMode();
        Unlocker unlocker = new Unlocker(app, mode, this);

        DisplayManager dm = app.getSystemService(DisplayManager.class);
        List<Display> displays = new ArrayList<>();
        for (Display d : dm.getDisplays()) {
            if (d.isValid() && (d.getFlags() & Display.FLAG_PRIVATE) == 0) displays.add(d);
        }
        Display primary = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (displays.isEmpty() && primary != null) displays.add(primary);

        Display secondary = null;
        for (Display d : displays) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) { secondary = d; break; }
        }
        Display patternDisplay;
        String pref = prefs.getPatternDisplay();
        if (Prefs.DISPLAY_PRIMARY.equals(pref) || secondary == null) patternDisplay = primary;
        else patternDisplay = secondary; // "auto" и "secondary" — нижний экран Thor
        int patternId = patternDisplay != null ? patternDisplay.getDisplayId() : Display.DEFAULT_DISPLAY;

        View focusTarget = null;
        for (Display d : displays) {
            try {
                Context dc = app.createDisplayContext(d);
                Context wc = Build.VERSION.SDK_INT >= 30
                        ? dc.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                        : dc;
                WindowManager wm = (WindowManager) wc.getSystemService(Context.WINDOW_SERVICE);
                boolean showPattern = d.getDisplayId() == patternId;
                LockPanel panel = new LockPanel(wc, unlocker, showPattern, "Устройство заблокировано", true);
                hideSystemBars(panel);
                wm.addView(panel, params());
                managers.add(wm);
                views.add(panel);
                if (d.getDisplayId() == Display.DEFAULT_DISPLAY || focusTarget == null) focusTarget = panel;
            } catch (Exception e) {
                Log.e(TAG, "Cannot add overlay on display " + d.getDisplayId(), e);
            }
        }
        if (focusTarget != null) focusTarget.requestFocus();
        return isShowing();
    }

    public void hide() {
        for (int i = 0; i < views.size(); i++) {
            try {
                managers.get(i).removeViewImmediate(views.get(i));
            } catch (Exception e) {
                Log.e(TAG, "remove failed", e);
            }
        }
        views.clear();
        managers.clear();
    }

    @Override
    public void onUnlocked() {
        hide();
        new Prefs(app).setLocked(false);
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
