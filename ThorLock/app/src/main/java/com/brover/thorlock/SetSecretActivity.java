package com.brover.thorlock;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Задание нового рисунка или комбинации: ввести и повторить. */
public class SetSecretActivity extends Activity {

    public static final String EXTRA_MODE = "mode";
    private static final int MIN_KEYS = 4;
    private static final int MAX_KEYS = 8;

    private Prefs prefs;
    private String mode;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView title, info, sequence;
    private PatternView patternView;
    private Button btnNext;

    private int stage = 0;          // 0 — ввод, 1 — подтверждение
    private String first;
    private int firstLen;
    private final List<Integer> keys = new ArrayList<>();
    private final GamepadInput gamepad = new GamepadInput();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = Prefs.MODE_PATTERN;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF0B0F14);

        title = label("", 20, 0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        info = label("", 15, 0xFF9AA7B8);
        root.addView(title);
        root.addView(info);

        if (Prefs.MODE_PATTERN.equals(mode)) {
            title.setText("Нарисуй новый узор");
            info.setText("Минимум 4 точки");
            patternView = new PatternView(this);
            patternView.setListener(this::onPattern);
            root.addView(patternView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            title.setText("Нажимай кнопки геймпада");
            info.setText("От " + MIN_KEYS + " до " + MAX_KEYS + " кнопок: A B X Y, крестовина, L1 R1 L2 R2, L3 R3, Start, Select");
            sequence = label("—", 30, 0xFF4FC3F7);
            sequence.setPadding(0, dp(24), 0, dp(24));
            root.addView(sequence);

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            Button reset = new Button(this);
            reset.setText("Сбросить");
            reset.setAllCaps(false);
            reset.setFocusable(false);
            reset.setOnClickListener(v -> restart("Нажимай кнопки геймпада"));
            btnNext = new Button(this);
            btnNext.setText("Далее");
            btnNext.setAllCaps(false);
            btnNext.setFocusable(false);
            btnNext.setEnabled(false);
            btnNext.setOnClickListener(v -> toConfirm());
            row.addView(reset);
            row.addView(btnNext);
            root.addView(row);
        }
        setContentView(root);
    }

    // ---------- рисунок ----------

    private void onPattern(String pattern, int count) {
        if (count < 4) {
            info.setText("Слишком короткий — минимум 4 точки");
            patternView.setError(true);
            handler.postDelayed(patternView::clear, 700);
            return;
        }
        if (stage == 0) {
            first = pattern;
            stage = 1;
            title.setText("Повтори узор");
            info.setText("Для подтверждения");
            handler.postDelayed(patternView::clear, 300);
        } else if (pattern.equals(first)) {
            prefs.setSecret(mode, pattern, count);
            done("Узор сохранён");
        } else {
            stage = 0;
            first = null;
            title.setText("Не совпало. Нарисуй заново");
            patternView.setError(true);
            handler.postDelayed(patternView::clear, 700);
        }
    }

    // ---------- геймпад ----------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (Prefs.MODE_GAMEPAD.equals(mode) && GamepadInput.isComboKey(e.getKeyCode())) {
            int p = gamepad.pressFromKey(e);
            if (p != 0) onPress(p);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (Prefs.MODE_GAMEPAD.equals(mode) && GamepadInput.isHatEvent(e)) {
            int p = gamepad.pressFromMotion(e);
            if (p != 0) onPress(p);
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private void onPress(int code) {
        if (stage == 0) {
            if (keys.size() >= MAX_KEYS) return;
            keys.add(code);
            sequence.setText(KeyNames.describe(keys));
            btnNext.setEnabled(keys.size() >= MIN_KEYS);
        } else {
            keys.add(code);
            sequence.setText(KeyNames.describe(keys));
            if (keys.size() >= firstLen) {
                String s = KeyNames.join(keys);
                if (s.equals(first)) {
                    prefs.setSecret(mode, s, firstLen);
                    done("Комбинация сохранена");
                } else {
                    restart("Не совпало. Введи комбинацию заново");
                }
            }
        }
    }

    private void toConfirm() {
        if (keys.size() < MIN_KEYS) return;
        first = KeyNames.join(keys);
        firstLen = keys.size();
        keys.clear();
        stage = 1;
        title.setText("Повтори комбинацию");
        info.setText("Нажми те же " + firstLen + " кнопок");
        sequence.setText("—");
        btnNext.setVisibility(View.GONE);
    }

    private void restart(String msg) {
        stage = 0;
        first = null;
        keys.clear();
        title.setText(msg);
        info.setText("От " + MIN_KEYS + " до " + MAX_KEYS + " кнопок");
        sequence.setText("—");
        btnNext.setVisibility(View.VISIBLE);
        btnNext.setEnabled(false);
    }

    private void done(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        finish();
    }

    private TextView label(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
