package com.brover.thorlock;

import android.view.KeyEvent;

import java.util.List;

/** Какие кнопки геймпада участвуют в комбинации и как их показывать. */
public final class KeyNames {
    private KeyNames() {}

    public static String name(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return "A";
            case KeyEvent.KEYCODE_BUTTON_B: return "B";
            case KeyEvent.KEYCODE_BUTTON_X: return "X";
            case KeyEvent.KEYCODE_BUTTON_Y: return "Y";
            case KeyEvent.KEYCODE_BUTTON_L1: return "L1";
            case KeyEvent.KEYCODE_BUTTON_R1: return "R1";
            case KeyEvent.KEYCODE_BUTTON_L2: return "L2";
            case KeyEvent.KEYCODE_BUTTON_R2: return "R2";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "L3";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "R3";
            case KeyEvent.KEYCODE_BUTTON_START: return "Start";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "Select";
            case KeyEvent.KEYCODE_DPAD_UP: return "↑";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "↓";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "←";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "→";
            default: return null;
        }
    }

    public static String describe(List<Integer> codes) {
        StringBuilder sb = new StringBuilder();
        for (int c : codes) {
            if (sb.length() > 0) sb.append("  ");
            String n = name(c);
            sb.append(n == null ? "?" : n);
        }
        return sb.toString();
    }

    public static String join(List<Integer> codes) {
        StringBuilder sb = new StringBuilder();
        for (int c : codes) {
            if (sb.length() > 0) sb.append(',');
            sb.append(c);
        }
        return sb.toString();
    }
}
