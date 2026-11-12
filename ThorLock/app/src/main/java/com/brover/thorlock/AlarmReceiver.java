package com.brover.thorlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        LockService s = LockService.instance;
        if (s != null) {
            s.checkDelayedLock();
        } else if (new Prefs(context).isEnabled()) {
            try {
                LockService.start(context, LockService.ACTION_REFRESH);
            } catch (Exception ignored) {
            }
        }
    }
}
