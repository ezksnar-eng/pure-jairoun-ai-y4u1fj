package com.jeron.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Foreground service that keeps long Studio runs / voice chats alive while the screen is off. */
public class KeepAliveService extends Service {

    static final String CHANNEL = "jeron_work";
    private PowerManager.WakeLock lock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String text = intent != null && intent.getStringExtra("text") != null
                ? intent.getStringExtra("text") : "Jeron";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Jeron",
                    NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pf = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, pf);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("Jeron").setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi).setOngoing(true);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1001, n);
        }
        if (lock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jeron:work");
                lock.setReferenceCounted(false);
                lock.acquire(8 * 60 * 60 * 1000L);
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (lock != null && lock.isHeld()) lock.release();
        } catch (Exception ignored) {
        }
        lock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
