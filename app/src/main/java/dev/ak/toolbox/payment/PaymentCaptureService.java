package dev.ak.toolbox.payment;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import dev.ak.toolbox.MainActivity;
import dev.ak.toolbox.R;

/** Prevents the active, short-lived local display session from being frozen. */
public final class PaymentCaptureService extends Service {
    private static final String CHANNEL = "rear_payment";
    private static volatile boolean ready;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) { stopSelf(); return START_NOT_STICKY; }
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "背屏付款码", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("背屏付款码运行中")
            .setContentText("退出付款码页面后自动停止")
            .setContentIntent(open).setOngoing(true).build();
        startForeground(14501, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        ready = true;
        return START_NOT_STICKY;
    }

    static boolean isReady() { return ready; }
    @Override public void onDestroy() { ready = false; PaymentSession.onForegroundStopped(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
