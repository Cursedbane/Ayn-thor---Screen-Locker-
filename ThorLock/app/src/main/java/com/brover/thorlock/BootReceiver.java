package com.brover.thorlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!new Prefs(context).isEnabled()) return;
        String action = Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                ? LockService.ACTION_BOOT
                : LockService.ACTION_REFRESH;
        try {
            LockService.start(context, action);
        } catch (Exception ignored) {
        }
    }
}
