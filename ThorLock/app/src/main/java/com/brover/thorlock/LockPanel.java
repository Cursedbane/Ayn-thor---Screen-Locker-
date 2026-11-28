package com.brover.thorlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

/**
 * Экран блокировки на одном дисплее.
 * Слои: фон (картинка/GIF) → затемнение → содержимое.
 * Сверху строка: время слева, заряд батареи справа.
 */
public class LockPanel extends FrameLayout {

    private final Unlocker unlocker;
    private final boolean handleKeys;
    private final TextView status;
    private final TextView progress;
    private final TextView footer;
    private final TextView battery;
    private final ImageView background;
    private boolean released;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBattery(intent);
        }
    };

    public LockPanel(Context c, Unlocker u, boolean showPattern, String title, boolean handleKeys) {
        super(c);
        this.unlocker = u;
        this.handleKeys = handleKeys;

        setBackgroundColor(0xFF0B0F14);
        setFocusable(true);
        setFocusableInTouchMode(true);

        // --- фон ---
        background = new ImageView(c);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(background, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        Prefs prefs = new Prefs(c);
        if (LockBackground.isSet(c)) {
            if (prefs.isBgDim()) {
                View dim = new View(c);
                dim.setBackgroundColor(0x8C000000);
                addView(dim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            }
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            LockBackground.load(c, w, h, this::applyBackground);
        }

        // --- содержимое ---
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        content.setPadding(pad, dp(8), pad, pad);
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Строка состояния: время + батарея.
        LinearLayout topBar = new LinearLayout(c);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        TextClock clock = new TextClock(c);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm");
        styleText(clock, 18, 0xFFFFFFFF, true);
        topBar.addView(clock, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        battery = new TextView(c);
        styleText(battery, 16, 0xFFFFFFFF, true);
        battery.setGravity(Gravity.END);
        topBar.addView(battery, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        content.addView(topBar, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout center = new LinearLayout(c);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        content.addView(center, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        boolean patternMode = Prefs.MODE_PATTERN.equals(u.getMode());

        center.addView(text("🔒", 36, 0xFFFFFFFF, false));
        center.addView(text(title, 20, 0xFFFFFFFF, true));

        String hint;
        if (patternMode) hint = L.t(c, showPattern ? R.string.hint_draw : R.string.hint_draw_other);
        else hint = L.t(c, R.string.hint_combo);
        center.addView(text(hint, 15, 0xFFC5CED9, false));

        status = text("", 15, 0xFFFF5252, false);
        center.addView(status);

        progress = text("", 30, 0xFF4FC3F7, false);
        progress.setVisibility(patternMode ? View.GONE : View.VISIBLE);
        center.addView(progress);

        if (patternMode && showPattern) {
            PatternView pv = new PatternView(c);
            pv.setListener((p, n) -> u.onPattern(pv, p, n));
            center.addView(pv, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        }

        footer = text("", 14, 0xFFFFD54F, false);
        footer.setVisibility(View.GONE);
        center.addView(footer);

        // Заряд сразу (последнее известное значение), дальше — обновления в onAttachedToWindow.
        updateBattery(c.getApplicationContext().registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));

        u.addPanel(this);
    }

    private boolean applyBackground(Drawable d) {
        if (released) return false;
        background.setImageDrawable(d);
        return true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Context app = getContext().getApplicationContext();
        Intent sticky;
        if (Build.VERSION.SDK_INT >= 33) sticky = app.registerReceiver(batteryReceiver, f, Context.RECEIVER_EXPORTED);
        else sticky = app.registerReceiver(batteryReceiver, f);
        updateBattery(sticky);
    }

    @Override
    protected void onDetachedFromWindow() {
        released = true;
        try {
            getContext().getApplicationContext().unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {
        }
        Drawable d = background.getDrawable();
        if (Build.VERSION.SDK_INT >= 28 && d instanceof AnimatedImageDrawable) {
            ((AnimatedImageDrawable) d).stop();
        }
        background.setImageDrawable(null);
        super.onDetachedFromWindow();
    }

    private void updateBattery(Intent i) {
        if (i == null) {
            battery.setText("");
            return;
        }
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) {
            battery.setText("");
            return;
        }
        int pct = Math.round(level * 100f / scale);
        int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        boolean charging = st == BatteryManager.BATTERY_STATUS_CHARGING;
        boolean full = st == BatteryManager.BATTERY_STATUS_FULL && plugged;
        String text;
        int color;
        if (charging) {
            text = "⚡ " + pct + "% · " + L.t(getContext(), R.string.batt_charging);
            color = 0xFF81C784;
        } else if (full) {
            text = "⚡ " + pct + "% · " + L.t(getContext(), R.string.batt_full);
            color = 0xFF81C784;
        } else {
            text = "🔋 " + pct + "%";
            color = pct <= 15 ? 0xFFFF5252 : 0xFFFFFFFF;
        }
        battery.setText(text);
        battery.setTextColor(color);
    }

    public void setStatus(String s, boolean error) {
        status.setText(s);
        status.setTextColor(error ? 0xFFFF5252 : 0xFFC5CED9);
    }

    public void setFooter(String s) {
        footer.setText(s);
        footer.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
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
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(4), 0, dp(4));
        styleText(t, sp, color, bold);
        return t;
    }

    /** Текст с тенью — читается на любом фоне. */
    private void styleText(TextView t, int sp, int color, boolean bold) {
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setShadowLayer(dp(3), 0, dp(1), 0xCC000000);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
