package com.brover.thorlock;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.List;

/**
 * Показывает окна блокировки поверх всего сразу на всех экранах (верхнем и нижнем),
 * а после разблокировки — видео, как загрузочный ролик на Steam Deck.
 */
public class LockOverlay implements Unlocker.Callback {

    private static final int TEST_SECONDS = 60;
    private static final long VIDEO_MAX_MS = 120_000;

    private interface ViewFactory {
        View create(Context c);
    }

    private final Context app;
    private final Context host;
    private final boolean accessibility;
    private final int windowType;
    private final Runnable onUnlocked;
    private final List<WindowManager> managers = new ArrayList<>();
    private final List<View> views = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Unlocker unlocker;
    private boolean test;
    private int testLeft;
    private boolean playingVideo;
    private VideoView videoView;

    private final Runnable testTick = new Runnable() {
        @Override
        public void run() {
            if (!isShowing() || !test) return;
            testLeft--;
            if (testLeft <= 0) {
                EventLog.add(app, "Тест: блокировка снята по таймеру");
                finishUnlock(false);
                return;
            }
            unlocker.footer("Тест: само снимется через " + testLeft + " с");
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable videoTimeout = () -> {
        EventLog.add(app, "Видео: остановлено по лимиту времени");
        stopVideo();
    };

    public LockOverlay(Context host, boolean accessibility, Runnable onUnlocked) {
        this.host = host;
        this.app = host.getApplicationContext();
        this.accessibility = accessibility;
        this.windowType = accessibility
                ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        this.onUnlocked = onUnlocked;
    }

    /** Кнопки, пришедшие из спецвозможностей. */
    public boolean handleKey(KeyEvent e) {
        if (playingVideo) return handleVideoKey(e);
        return isShowing() && unlocker != null && unlocker.handleKey(e);
    }

    /** Экран блокировки на экране (видео не считается). */
    public boolean isShowing() { return !views.isEmpty() && !playingVideo; }

    public boolean isTest() { return test; }

    public boolean isPlayingVideo() { return playingVideo; }

    private boolean canDraw() {
        if (!accessibility && !Settings.canDrawOverlays(app)) {
            EventLog.add(app, "ОШИБКА: нет разрешения «Поверх других окон»");
            return false;
        }
        return true;
    }

    private List<Display> publicDisplays(boolean log) {
        DisplayManager dm = app.getSystemService(DisplayManager.class);
        List<Display> displays = new ArrayList<>();
        for (Display d : dm.getDisplays()) {
            if (log) {
                EventLog.add(app, "  экран id=" + d.getDisplayId() + " «" + d.getName() + "» flags="
                        + d.getFlags() + " state=" + d.getState());
            }
            if (d.isValid() && (d.getFlags() & Display.FLAG_PRIVATE) == 0) displays.add(d);
        }
        Display primary = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (displays.isEmpty() && primary != null) displays.add(primary);
        return displays;
    }

    // ================= блокировка =================

    public boolean show(boolean testMode) {
        if (playingVideo) stopVideoSilently();
        if (isShowing()) return true;
        if (!canDraw()) return false;
        Prefs prefs = new Prefs(app);
        String mode = prefs.getMode();
        boolean patternMode = Prefs.MODE_PATTERN.equals(mode);
        test = testMode;
        unlocker = new Unlocker(app, mode, this);
        EventLog.add(app, "Показ блокировки" + (test ? " (тест)" : "") + ", способ: "
                + (patternMode ? "рисунок" : "геймпад"));

        List<Display> displays = publicDisplays(true);
        Display primary = app.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
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

        if (views.isEmpty()) return false;
        views.get(0).requestFocus();

        if (test) {
            testLeft = TEST_SECONDS;
            unlocker.footer("Тест: само снимется через " + testLeft + " с");
            handler.postDelayed(testTick, 1000);
        }
        return true;
    }

    private boolean addPanel(Display d, boolean showPattern) {
        return addWindow(d, c -> new LockPanel(c, unlocker, showPattern, "Устройство заблокировано", true),
                showPattern ? "рисунок" : "блокировка");
    }

    /** Убрать все окна (и блокировку, и видео). */
    public void hide() {
        handler.removeCallbacks(testTick);
        handler.removeCallbacks(videoTimeout);
        if (videoView != null) {
            try {
                videoView.stopPlayback();
            } catch (Throwable ignored) {
            }
            videoView = null;
        }
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
        playingVideo = false;
    }

    /** Верный пароль. */
    @Override
    public void onUnlocked() {
        finishUnlock(true);
    }

    private void finishUnlock(boolean allowVideo) {
        hide();
        new Prefs(app).setLocked(false);
        EventLog.add(app, "Разблокировано");
        if (allowVideo && playVideo()) return; // onUnlocked вызовется после видео
        if (onUnlocked != null) onUnlocked.run();
    }

    // ================= видео после разблокировки =================

    /** Проверка видео из настроек. */
    public void testVideo() {
        if (isShowing() || playingVideo) return;
        if (!playVideo()) EventLog.add(app, "Видео: не выбрано или выключено");
    }

    private boolean playVideo() {
        Prefs prefs = new Prefs(app);
        String uriStr = prefs.getVideoUri();
        if (!prefs.isVideoEnabled() || uriStr == null || !canDraw()) return false;
        Uri uri = Uri.parse(uriStr);
        boolean muted = prefs.isVideoMuted();
        playingVideo = true;

        boolean videoAdded = false;
        for (Display d : publicDisplays(false)) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) {
                videoAdded = addWindow(d, c -> createVideoView(c, uri, muted), "видео");
            } else {
                addWindow(d, this::createBlackView, "чёрный фон");
            }
        }
        if (!videoAdded) {
            EventLog.add(app, "Видео: не удалось показать окно");
            hide();
            return false;
        }
        EventLog.add(app, "Видео: запуск");
        handler.postDelayed(videoTimeout, VIDEO_MAX_MS);
        return true;
    }

    private View createVideoView(Context c, Uri uri, boolean muted) {
        FrameLayout root = createBlackView(c);
        VideoView vv = new VideoView(c);
        vv.setOnPreparedListener(mp -> {
            if (muted) mp.setVolume(0f, 0f);
            vv.start();
        });
        vv.setOnCompletionListener(mp -> stopVideo());
        vv.setOnErrorListener((mp, what, extra) -> {
            EventLog.add(app, "Видео: ошибка воспроизведения (" + what + "/" + extra
                    + "). Выбери файл заново или другой формат (mp4/H.264).");
            stopVideo();
            return true;
        });
        root.addView(vv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        videoView = vv;
        try {
            vv.setVideoURI(uri);
        } catch (Throwable e) {
            EventLog.add(app, "Видео: не открылось: " + e);
            handler.post(this::stopVideo);
        }
        return root;
    }

    private FrameLayout createBlackView(Context c) {
        FrameLayout f = new FrameLayout(c) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (handleVideoKey(e)) return true;
                return super.dispatchKeyEvent(e);
            }
        };
        f.setBackgroundColor(0xFF000000);
        f.setFocusable(true);
        f.setFocusableInTouchMode(true);
        f.setOnClickListener(v -> stopVideo()); // тап — пропустить
        return f;
    }

    /** Любая кнопка (кроме громкости) пропускает видео. */
    private boolean handleVideoKey(KeyEvent e) {
        if (!playingVideo) return false;
        int kc = e.getKeyCode();
        if (kc == KeyEvent.KEYCODE_VOLUME_UP || kc == KeyEvent.KEYCODE_VOLUME_DOWN
                || kc == KeyEvent.KEYCODE_VOLUME_MUTE || kc == KeyEvent.KEYCODE_POWER) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) stopVideo();
        return true;
    }

    private void stopVideo() {
        if (!playingVideo) return;
        hide();
        EventLog.add(app, "Видео: конец");
        if (onUnlocked != null) onUnlocked.run();
    }

    private void stopVideoSilently() {
        hide();
    }

    // ================= окна =================

    private boolean addWindow(Display d, ViewFactory f, String label) {
        try {
            Context wc;
            if (Build.VERSION.SDK_INT >= 30) {
                wc = host.createDisplayContext(d).createWindowContext(windowType, null);
            } else {
                wc = d.getDisplayId() == Display.DEFAULT_DISPLAY ? host : host.createDisplayContext(d);
            }
            if (attach(wc, d, f, label)) return true;
        } catch (Throwable e) {
            EventLog.add(app, "  окно «" + label + "» на экране " + d.getDisplayId() + " — ОШИБКА: " + e);
        }
        // Запасной путь для основного экрана: прямо через контекст службы.
        if (d.getDisplayId() == Display.DEFAULT_DISPLAY) {
            try {
                return attach(host, d, f, label);
            } catch (Throwable e) {
                EventLog.add(app, "  окно «" + label + "» на основном экране (запасной путь) — ОШИБКА: " + e);
            }
        }
        return false;
    }

    private boolean attach(Context wc, Display d, ViewFactory f, String label) {
        WindowManager wm = (WindowManager) wc.getSystemService(Context.WINDOW_SERVICE);
        View v = f.create(wc);
        hideSystemBars(v);
        try {
            wm.addView(v, params(windowType));
        } catch (RuntimeException e) {
            if (v instanceof LockPanel && unlocker != null) unlocker.removePanel((LockPanel) v);
            throw e;
        }
        managers.add(wm);
        views.add(v);
        EventLog.add(app, "  окно «" + label + "» на экране " + d.getDisplayId()
                + (accessibility ? " [спецвозм.]" : "") + " — ок");
        return true;
    }

    private static WindowManager.LayoutParams params(int type) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
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
