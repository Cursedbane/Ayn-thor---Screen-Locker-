package com.brover.thorlock;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Превращает события геймпада в "нажатия" для комбинации.
 * Понимает и обычные кнопки, и D-pad, который приходит как ось HAT.
 */
public final class GamepadInput {
    private int lastHat = 0;

    public static boolean isComboKey(int keyCode) {
        return KeyNames.name(keyCode) != null;
    }

    /** Код нажатой кнопки, если это новое нажатие (не повтор и не отпускание), иначе 0. */
    public int pressFromKey(KeyEvent e) {
        if (!isComboKey(e.getKeyCode())) return 0;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) return e.getKeyCode();
        return 0;
    }

    public static boolean isHatEvent(MotionEvent e) {
        return (e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                && e.getActionMasked() == MotionEvent.ACTION_MOVE;
    }

    /** Код направления D-pad при новом нажатии через HAT, иначе 0. */
    public int pressFromMotion(MotionEvent e) {
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        int dir = 0;
        if (hx < -0.5f) dir = KeyEvent.KEYCODE_DPAD_LEFT;
        else if (hx > 0.5f) dir = KeyEvent.KEYCODE_DPAD_RIGHT;
        else if (hy < -0.5f) dir = KeyEvent.KEYCODE_DPAD_UP;
        else if (hy > 0.5f) dir = KeyEvent.KEYCODE_DPAD_DOWN;
        if (dir == lastHat) return 0;
        lastHat = dir;
        return dir;
    }
}
