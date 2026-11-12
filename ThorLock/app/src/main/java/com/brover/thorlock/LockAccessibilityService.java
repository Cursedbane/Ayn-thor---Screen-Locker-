package com.brover.thorlock;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Основной способ работы. Службу спецвозможностей система держит постоянно,
 * её не убивают при сворачивании/смахивании приложения. Плюс она перехватывает
 * кнопки геймпада и рисует блокировку поверх шторки уведомлений.
 */
public class LockAccessibilityService extends AccessibilityService {

    private LockEngine engine;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // Фоновая служба больше не нужна — спецвозможности надёжнее.
        LockService.stop(this);
        engine = new LockEngine(this, true, null);
        engine.start();
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        return engine != null && engine.onKeyEvent(event);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Пока заблокировано — не даём открыть шторку уведомлений.
        if (engine == null || !engine.isShowing() || event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg != null && "com.android.systemui".contentEquals(pkg) && Build.VERSION.SDK_INT >= 31) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        if (engine != null) {
            engine.stop();
            engine = null;
            Prefs p = new Prefs(this);
            if (p.isEnabled()) LockEngine.send(this, LockEngine.ACTION_REFRESH); // откат на фоновую службу
        }
        super.onDestroy();
    }
}
