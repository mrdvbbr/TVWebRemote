package com.bbr.tvwebremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "TVWebRemoteBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : "";
        Log.i(TAG, "Boot broadcast received: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            Intent serviceIntent = new Intent(context, WebRemoteService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start WebRemoteService on boot: " + e.getMessage());
            }
        }
    }
}
