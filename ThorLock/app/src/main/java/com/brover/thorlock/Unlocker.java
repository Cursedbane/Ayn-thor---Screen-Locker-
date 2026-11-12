package com.brover.thorlock;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

/** Логика проверки рисунка/комбинации. Общая для всех экранов блокировки. */
public class Unlocker {

    public interface Callback {
        void onUnlocked();
    }

    private static final int MIN_PATTERN = 4;
    private static final int FREE_ATTEMPTS = 5;

    // Счётчик ошибок общий на процесс, чтобы нельзя было сбросить его переоткрытием окна.
    private static int fails = 0;
    private static long blockedUntil = 0;

    private final Prefs prefs;
    private final String mode;
    private final Callback callback;
    private final int length;
    private final List<LockPanel> panels = new ArrayList<>();
    private final List<Integer> combo = new ArrayList<>();
    private final GamepadInput gamepad = new GamepadInput();
    private final Handler handler = new Handler(Looper.getMainLooper());

    public Unlocker(Context c, String mode, Callback cb) {
        this.prefs = new Prefs(c);
        this.mode = mode;
        this.callback = cb;
        this.length = prefs.getSecretLength(mode);
    }

    public String getMode() { return mode; }

    void addPanel(LockPanel p) {
        panels.add(p);
        p.setProgress(combo.size(), length);
    }

    public void onPattern(PatternView v, String pattern, int count) {
        if (isBlocked()) {
            v.clear();
            return;
        }
        if (count < MIN_PATTERN) {
            status("Минимум " + MIN_PATTERN + " точки", true);
            v.setError(true);
            handler.postDelayed(v::clear, 600);
            return;
        }
        if (prefs.check(mode, pattern)) {
            success();
        } else {
            fail();
            v.setError(true);
            v.setInputEnabled(false);
            handler.postDelayed(() -> {
                v.clear();
                v.setInputEnabled(true);
            }, 900);
        }
    }

    /** true — событие съедено. */
    public boolean handleKey(KeyEvent e) {
        int kc = e.getKeyCode();
        if (kc == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) resetCombo();
            return true;
        }
        if (!GamepadInput.isComboKey(kc)) return false; // громкость и т.п. пропускаем
        int p = gamepad.pressFromKey(e);
        if (p != 0) onPress(p);
        return true;
    }

    public boolean handleMotion(MotionEvent e) {
        if (!GamepadInput.isHatEvent(e)) return false;
        int p = gamepad.pressFromMotion(e);
        if (p != 0) onPress(p);
        return true;
    }

    private void onPress(int code) {
        if (!Prefs.MODE_GAMEPAD.equals(mode)) {
            status("Разблокировка рисунком на экране", false);
            return;
        }
        if (isBlocked()) return;
        combo.add(code);
        progress();
        if (combo.size() >= length) {
            String entered = KeyNames.join(combo);
            combo.clear();
            if (prefs.check(mode, entered)) {
                success();
            } else {
                fail();
                progress();
            }
        }
    }

    private void resetCombo() {
        combo.clear();
        progress();
    }

    private boolean isBlocked() {
        long left = blockedUntil - SystemClock.elapsedRealtime();
        if (left > 0) {
            status("Слишком много попыток. Подожди " + (left / 1000 + 1) + " с", true);
            return true;
        }
        return false;
    }

    private void fail() {
        fails++;
        if (fails >= FREE_ATTEMPTS) {
            int extra = Math.min(fails - FREE_ATTEMPTS, 4);
            blockedUntil = SystemClock.elapsedRealtime() + 30_000L * (1L << extra);
            isBlocked();
        } else {
            status("Неверно. Осталось попыток: " + (FREE_ATTEMPTS - fails), true);
        }
    }

    private void success() {
        fails = 0;
        blockedUntil = 0;
        status("", false);
        callback.onUnlocked();
    }

    private void progress() {
        for (LockPanel p : panels) p.setProgress(combo.size(), length);
    }

    private void status(String s, boolean err) {
        for (LockPanel p : panels) p.setStatus(s, err);
    }
}
