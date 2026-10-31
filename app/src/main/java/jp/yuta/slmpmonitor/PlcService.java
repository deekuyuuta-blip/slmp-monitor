package jp.yuta.slmpmonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * 待受中だけ動く常駐サービス（通知を出す）。
 * これがないと、画面を消したり他のアプリに切り替えたときにAndroidがアプリを止め、
 * PLCへの返事が途切れる。CPUも眠らないようにする。
 */
public final class PlcService extends Service {

    private static final String CHANNEL = "slmp_server";
    private static final int ID = 1;
    private PowerManager.WakeLock cpuLock;

    static void start(Context c) {
        Intent i = new Intent(c, PlcService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (RuntimeException ignored) {
            // 起動できなくても、前面にいる間の通信はできる
        }
    }

    static void stop(Context c) {
        try {
            c.stopService(new Intent(c, PlcService.class));
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(ID, n);
            }
        } catch (RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (cpuLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "slmp-monitor:server");
                cpuLock.setReferenceCounted(false);
                cpuLock.acquire();
            }
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "PLC待受", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("SLMPモニタ 待受中")
                .setContentText("PLCからの通信を待っています（ポート " + PlcLink.serverPort() + "）")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        cpuLock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
