package com.brover.thorlock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Сетка 3x3 как в обычной блокировке Android. */
public class PatternView extends View {

    public interface Listener {
        void onPatternDone(String pattern, int count);
    }

    private static final int COLOR_DOT = 0xFF8A97A8;
    private static final int COLOR_ACTIVE = 0xFF4FC3F7;
    private static final int COLOR_ERROR = 0xFFFF5252;

    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float[] cx = new float[9];
    private final float[] cy = new float[9];
    private final List<Integer> path = new ArrayList<>();

    private float dotR, hitR;
    private float fingerX, fingerY;
    private boolean drawing;
    private boolean error;
    private boolean inputEnabled = true;
    private Listener listener;

    public PatternView(Context c) {
        super(c);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setStyle(Paint.Style.STROKE);
    }

    public void setListener(Listener l) { listener = l; }

    public void clear() {
        path.clear();
        error = false;
        drawing = false;
        invalidate();
    }

    public void setError(boolean e) {
        error = e;
        invalidate();
    }

    public void setInputEnabled(boolean e) { inputEnabled = e; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float size = Math.min(w, h) * 0.92f;
        float cell = size / 3f;
        float offX = (w - size) / 2f;
        float offY = (h - size) / 2f;
        for (int i = 0; i < 9; i++) {
            cx[i] = offX + cell * (i % 3) + cell / 2f;
            cy[i] = offY + cell * (i / 3) + cell / 2f;
        }
        dotR = cell * 0.07f;
        hitR = cell * 0.36f;
        linePaint.setStrokeWidth(cell * 0.06f);
        ringPaint.setStrokeWidth(cell * 0.025f);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!inputEnabled) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                clear();
                drawing = true;
                fingerX = e.getX();
                fingerY = e.getY();
                tryHit(fingerX, fingerY);
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getHistorySize(); i++) tryHit(e.getHistoricalX(i), e.getHistoricalY(i));
                fingerX = e.getX();
                fingerY = e.getY();
                tryHit(fingerX, fingerY);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                drawing = false;
                if (!path.isEmpty() && listener != null) listener.onPatternDone(patternString(), path.size());
                break;
        }
        invalidate();
        return true;
    }

    private void tryHit(float x, float y) {
        for (int i = 0; i < 9; i++) {
            if (path.contains(i)) continue;
            float dx = x - cx[i], dy = y - cy[i];
            if (dx * dx + dy * dy > hitR * hitR) continue;
            if (!path.isEmpty()) {
                // Если перепрыгнули через точку посередине — добавляем и её, как в Android.
                int last = path.get(path.size() - 1);
                int r1 = last / 3, c1 = last % 3, r2 = i / 3, c2 = i % 3;
                if ((r1 + r2) % 2 == 0 && (c1 + c2) % 2 == 0) {
                    int mid = ((r1 + r2) / 2) * 3 + (c1 + c2) / 2;
                    if (!path.contains(mid)) path.add(mid);
                }
            }
            path.add(i);
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            return;
        }
    }

    private String patternString() {
        StringBuilder sb = new StringBuilder();
        for (int p : path) {
            if (sb.length() > 0) sb.append('-');
            sb.append(p);
        }
        return sb.toString();
    }

    @Override
    protected void onDraw(Canvas c) {
        int active = error ? COLOR_ERROR : COLOR_ACTIVE;
        linePaint.setColor(active);
        linePaint.setAlpha(170);
        for (int i = 1; i < path.size(); i++) {
            int a = path.get(i - 1), b = path.get(i);
            c.drawLine(cx[a], cy[a], cx[b], cy[b], linePaint);
        }
        if (drawing && !path.isEmpty()) {
            int last = path.get(path.size() - 1);
            c.drawLine(cx[last], cy[last], fingerX, fingerY, linePaint);
        }
        for (int i = 0; i < 9; i++) {
            boolean on = path.contains(i);
            dotPaint.setColor(on ? active : COLOR_DOT);
            c.drawCircle(cx[i], cy[i], on ? dotR * 1.6f : dotR, dotPaint);
            if (on) {
                ringPaint.setColor(active);
                c.drawCircle(cx[i], cy[i], hitR * 0.8f, ringPaint);
            }
        }
    }
}
