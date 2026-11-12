package com.brover.thorlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        LockEngine e = LockEngine.current;
        if (e != null) {
            e.checkDelayedLock();
        } else if (new Prefs(context).isEnabled()) {
            LockEngine.send(context, LockEngine.ACTION_REFRESH);
        }
    }
}
