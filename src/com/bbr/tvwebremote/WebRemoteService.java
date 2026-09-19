package com.bbr.tvwebremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

public class WebRemoteService extends Service {
    private static final String TAG = "WebRemoteService";
    private static final String CHANNEL_ID = "tv_web_remote_service";
    private static final int NOTIFICATION_ID = 1001;

    private HttpServer server;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = buildNotification();
        try {
            startForeground(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            Log.w(TAG, "startForeground error: " + e.getMessage());
        }

        server = new HttpServer(getApplicationContext(), 8080);
        server.start();
        Log.i(TAG, "WebRemoteService created, HttpServer running on port 8080");

        KeyDispatcher.ensureTvKeyDaemon(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (server == null || !server.isRunning()) {
            server = new HttpServer(getApplicationContext(), 8080);
            server.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "TV Web Remote",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("TV Web Remote Control Dashboard");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle("TV Web Remote")
                .setContentText("Active on port 8080")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true);
        return builder.build();
    }
}
