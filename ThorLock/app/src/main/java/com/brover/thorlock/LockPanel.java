package com.brover.thorlock;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Содержимое экрана блокировки на одном дисплее. */
public class LockPanel extends LinearLayout {

    private final Unlocker unlocker;
    private final boolean handleKeys;
    private final TextView status;
    private final TextView progress;

    public LockPanel(Context c, Unlocker u, boolean showPattern, String title, boolean handleKeys) {
        super(c);
        this.unlocker = u;
        this.handleKeys = handleKeys;

        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setBackgroundColor(0xFF0B0F14);
        int pad = dp(16);
        setPadding(pad, pad, pad, pad);
        setFocusable(true);
        setFocusableInTouchMode(true);

        boolean patternMode = Prefs.MODE_PATTERN.equals(u.getMode());

        addView(text("🔒", 36, 0xFFFFFFFF, false));
        addView(text(title, 20, 0xFFFFFFFF, true));

        String hint;
        if (patternMode) hint = showPattern ? "Нарисуй узор" : "Нарисуй узор на другом экране";
        else hint = "Введи комбинацию кнопками геймпада";
        addView(text(hint, 15, 0xFF9AA7B8, false));

        status = text("", 15, 0xFFFF5252, false);
        addView(status);

        progress = text("", 30, 0xFF4FC3F7, false);
        progress.setVisibility(patternMode ? View.GONE : View.VISIBLE);
        addView(progress);

        if (patternMode && showPattern) {
            PatternView pv = new PatternView(c);
            pv.setListener((p, n) -> u.onPattern(pv, p, n));
            addView(pv, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        }

        u.addPanel(this);
    }

    public void setStatus(String s, boolean error) {
        status.setText(s);
        status.setTextColor(error ? 0xFFFF5252 : 0xFF9AA7B8);
    }

    public void setProgress(int entered, int total) {
        if (total <= 0) {
            progress.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < total; i++) {
            if (i > 0) sb.append(' ');
            sb.append(i < entered ? '●' : '○');
        }
        progress.setText(sb.toString());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (handleKeys && unlocker.handleKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (handleKeys && unlocker.handleMotion(e)) return true;
        return super.dispatchGenericMotionEvent(e);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
