package dev.akai.toolbox.payment;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;
import android.os.SystemClock;
import dev.akai.toolbox.BuildConfig;

/** Uses the system-reported sender UID; unrecognized or hidden senders fail closed. */
public final class PaymentPageReceiver extends BroadcastReceiver {
    public static final String ACTION = "dev.akai.toolbox.payment.PAGE";
    public static final String PROBE = "dev.akai.toolbox.payment.PROBE";
    private static final PaymentEventGate EVENTS = new PaymentEventGate();

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!ACTION.equals(action) && !(BuildConfig.DEBUG && PROBE.equals(action))) return;
        int uid = getSentFromUid();
        String pkg = verifiedPackage(context, uid);
        if (BuildConfig.DEBUG && PROBE.equals(action)) {
            long sent = intent.getLongExtra("sentElapsed", 0);
            Log.i("AkaiPaymentProbe", "probe received uid=" + uid + " matched=" + (pkg != null)
                + (sent > 0 ? " kind=" + intent.getStringExtra("kind") + " ageMs=" + (android.os.SystemClock.elapsedRealtime() - sent) : ""));
        }
        if (pkg == null) return;
        if (BuildConfig.DEBUG && PROBE.equals(action)) {
            if (isOrderedBroadcast()) setResultCode(Activity.RESULT_OK);
            return;
        }
        String command = intent.getStringExtra("command");
        long eventAt = intent.getLongExtra("eventAt", -1);
        long now = SystemClock.elapsedRealtimeNanos();
        if (!EVENTS.accept(uid, eventAt, now)) return;
        if (BuildConfig.DEBUG) Log.i("AkaiPaymentProbe", "page notification=" + command + " from=" + pkg);
        if ("start".equals(command)) PaymentSession.start(context, uid, pkg);
        else if ("suspend".equals(command)) PaymentSession.suspend(uid);
        else if ("stop".equals(command)) PaymentSession.stop(context, uid);
    }

    private String verifiedPackage(Context context, int uid) {
        PackageManager pm = context.getPackageManager();
        for (String pkg : new String[] {"com.tencent.mm", "com.eg.android.AlipayGphone"}) {
            try { if (pm.getPackageUid(pkg, 0) == uid) return pkg; }
            catch (PackageManager.NameNotFoundException ignored) { }
        }
        return null;
    }
}
