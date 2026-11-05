package dev.ak.toolbox.payment;

import android.content.Context;

/** Debug-only entry point for the locally generated, non-payment test app. */
public final class PaymentTestHarness {
    private PaymentTestHarness() { }
    public static void start(Context context, int uid) throws Exception {
        Context app = context.getApplicationContext();
        if (app.getPackageManager().getPackageUid("dev.ak.secureprobe", 0) != uid)
            throw new IllegalArgumentException("不是合成测试应用");
        PaymentSession.startInternal(app, uid, "synthetic");
    }
}
